package com.chunking.engine.scheduler;

import com.chunking.engine.core.ChunkEngine;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded-budget main-thread pacer for {@code ChunkStatusTasks.full} chunk promotions.
 *
 * <p>Bypassed completely during headless pregeneration (0 players and no active client frames),
 * synchronous {@code managedBlock} waits, and chunks within Chebyshev distance 2 of any player
 * so raw Chunky throughput stays uncapped and players never stall or fall through unloaded terrain.
 */
public final class LoadPacer {
    public static final int PLAYER_BYPASS_RADIUS = 2;
    public static final long CALM_BUDGET_NS = 10_000_000L;
    public static final long MILD_BUDGET_NS = 7_500_000L;
    public static final long SEVERE_BUDGET_NS = 6_000_000L;
    private static final long STARVATION_SLICE_NS = 2_000_000L;
    private static final long FALLBACK_TICK_WINDOW_NS = 50_000_000L;

    private static final class PendingPromotion<T> {
        final int chunkX;
        final int chunkZ;
        final String dimensionKey;
        final Supplier<T> work;
        final CompletableFuture<T> future;

        PendingPromotion(
                int chunkX,
                int chunkZ,
                String dimensionKey,
                Supplier<T> work,
                CompletableFuture<T> future
        ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.dimensionKey = dimensionKey;
            this.work = work;
            this.future = future;
        }

        void run() {
            try {
                this.future.complete(this.work.get());
            } catch (Throwable t) {
                this.future.completeExceptionally(t);
            }
        }
    }

    private static final class PacerQueue {
        final ConcurrentLinkedQueue<PendingPromotion<?>> ingress = new ConcurrentLinkedQueue<>();
        // Accessed exclusively on the owning server main thread.
        final ArrayList<PendingPromotion<?>> ready = new ArrayList<>(32);
        long tickStamp = Long.MIN_VALUE;
        long spentNsThisTick = 0L;
        int drainedThisTick = 0;
        int drainedLastTick = 1;
        volatile ServerLevel level;
    }

    private static final ConcurrentHashMap<Executor, PacerQueue> QUEUES = new ConcurrentHashMap<>(4);
    private static final AtomicInteger GLOBAL_PENDING = new AtomicInteger();
    private static final AtomicBoolean HOOKS_REGISTERED = new AtomicBoolean();
    private static volatile boolean stopping;

    private LoadPacer() {}

    public static boolean isLiveGameplay() {
        return TickBudget.hasOnlinePlayers() || FrameGuard.get().hasActiveClientFrames();
    }

    public static boolean hasPending() {
        return GLOBAL_PENDING.get() > 0;
    }

    public static int pendingCount() {
        return Math.max(0, GLOBAL_PENDING.get());
    }

    public static long computeTickBudgetNs(
            boolean liveGameplay,
            double effectiveTickMs,
            boolean serverPressured,
            FrameGuard.Level level
    ) {
        if (!liveGameplay) {
            return Long.MAX_VALUE;
        }
        if (level == FrameGuard.Level.SEVERE || effectiveTickMs >= 38.0D) {
            return SEVERE_BUDGET_NS;
        }
        if (level == FrameGuard.Level.MILD || serverPressured || effectiveTickMs >= 28.0D) {
            return MILD_BUDGET_NS;
        }
        return CALM_BUDGET_NS;
    }

    public static boolean shouldPace(ServerLevel level, int chunkX, int chunkZ) {
        if (!isLiveGameplay() || TickBudget.isInManagedBlock()) {
            return false;
        }
        if (level != null) {
            MinecraftServer server = level.getServer();
            if (server != null && !server.isRunning()) {
                return false;
            }
        }
        String dimKey = level != null ? level.dimension().identifier().toString() : null;
        return !isNearAnyPlayer(dimKey, chunkX, chunkZ);
    }

    public static boolean shouldPace(String dimensionKey, int chunkX, int chunkZ) {
        if (!isLiveGameplay() || TickBudget.isInManagedBlock()) {
            return false;
        }
        return !isNearAnyPlayer(dimensionKey, chunkX, chunkZ);
    }

    public static <T> CompletableFuture<T> enqueue(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            Supplier<T> work,
            Executor mainThreadExecutor
    ) {
        if (work == null || mainThreadExecutor == null) {
            throw new IllegalArgumentException("work and mainThreadExecutor must be non-null");
        }
        ensureHooksRegistered();
        String dimKey = level != null ? level.dimension().identifier().toString() : null;
        return enqueueInternal(level, dimKey, chunkX, chunkZ, work, mainThreadExecutor);
    }

    public static <T> CompletableFuture<T> enqueue(
            String dimensionKey,
            int chunkX,
            int chunkZ,
            Supplier<T> work,
            Executor mainThreadExecutor
    ) {
        if (work == null || mainThreadExecutor == null) {
            throw new IllegalArgumentException("work and mainThreadExecutor must be non-null");
        }
        return enqueueInternal(null, dimensionKey, chunkX, chunkZ, work, mainThreadExecutor);
    }

    private static <T> CompletableFuture<T> enqueueInternal(
            ServerLevel level,
            String dimKey,
            int chunkX,
            int chunkZ,
            Supplier<T> work,
            Executor mainThreadExecutor
    ) {
        if (stopping) {
            return CompletableFuture.supplyAsync(work, mainThreadExecutor);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        PacerQueue queue = QUEUES.computeIfAbsent(mainThreadExecutor, k -> new PacerQueue());
        if (level != null) {
            queue.level = level;
        }
        GLOBAL_PENDING.incrementAndGet();
        queue.ingress.offer(new PendingPromotion<>(chunkX, chunkZ, dimKey, work, future));
        return future;
    }

    /**
     * Executes at most one highest-priority queued promotion for {@code mainThreadExecutor} if
     * budget remains or an immediate bypass condition holds. Must be called on the server main thread.
     */
    public static boolean pollOne(Executor mainThreadExecutor) {
        if (mainThreadExecutor == null || GLOBAL_PENDING.get() <= 0) {
            return false;
        }
        PacerQueue queue = QUEUES.get(mainThreadExecutor);
        if (queue == null) {
            return false;
        }
        pullIngress(queue);
        if (queue.ready.isEmpty()) {
            return false;
        }

        ServerLevel level = queue.level;
        MinecraftServer server = level != null ? level.getServer() : null;
        long stamp = server != null ? server.getTickCount() : (System.nanoTime() / FALLBACK_TICK_WINDOW_NS);
        advanceTickWindow(queue, stamp);

        boolean live = isLiveGameplay();
        boolean serverStopping = server != null && !server.isRunning();
        FrameGuard gov = FrameGuard.get();
        long budgetNs = computeTickBudgetNs(
                live, TickBudget.lastRecordedTickMs(), gov.isServerTickPressured(), gov.level());
        boolean forceBypass = !live || serverStopping || TickBudget.isInManagedBlock();

        return runBestIfAllowed(queue, budgetNs, forceBypass);
    }

    /**
     * Drains queued promotions for {@code mainThreadExecutor} up to {@code budgetNs} (or completely
     * when {@code forceBypass} is true). Intended for deterministic testing and tick-start safety drains.
     */
    public static int drainBudgeted(Executor mainThreadExecutor, long budgetNs, boolean forceBypass) {
        if (mainThreadExecutor == null) {
            return 0;
        }
        PacerQueue queue = QUEUES.get(mainThreadExecutor);
        if (queue == null) {
            return 0;
        }
        pullIngress(queue);
        if (queue.ready.isEmpty()) {
            return 0;
        }
        long startNs = System.nanoTime();
        int ran = 0;
        while (!queue.ready.isEmpty()) {
            if (!forceBypass && ran > 0 && (System.nanoTime() - startNs) >= budgetNs) {
                break;
            }
            MoveTracker.PlayerMotion[] motions = activeMotionsFor(queue);
            int bestIdx = selectBestIndex(queue.ready, motions);
            if (bestIdx < 0) {
                break;
            }
            PendingPromotion<?> best = removeSwapLast(queue.ready, bestIdx);
            long t0 = System.nanoTime();
            decrementPending();
            best.run();
            queue.spentNsThisTick += Math.max(0L, System.nanoTime() - t0);
            queue.drainedThisTick++;
            ran++;
        }
        return ran;
    }

    public static void flushAll() {
        stopping = true;
        boolean drained;
        do {
            drained = false;
            for (PacerQueue queue : QUEUES.values()) {
                pullIngress(queue);
                while (!queue.ready.isEmpty()) {
                    PendingPromotion<?> item = queue.ready.remove(queue.ready.size() - 1);
                    decrementPending();
                    item.run();
                    drained = true;
                }
            }
        } while (drained);
        QUEUES.clear();
        GLOBAL_PENDING.set(0);
    }

    public static void resetSession() {
        stopping = false;
        QUEUES.clear();
        GLOBAL_PENDING.set(0);
    }

    public static void resetForTest() {
        resetSession();
    }

    private static void onServerTickStart(MinecraftServer server) {
        if (GLOBAL_PENDING.get() <= 0) {
            return;
        }
        boolean live = isLiveGameplay();
        boolean stopping = server != null && !server.isRunning();
        long tickStamp = server != null ? server.getTickCount() : (System.nanoTime() / FALLBACK_TICK_WINDOW_NS);

        for (PacerQueue queue : QUEUES.values()) {
            pullIngress(queue);
            if (queue.ready.isEmpty()) {
                continue;
            }
            advanceTickWindow(queue, tickStamp);
            if (!live || stopping || TickBudget.isInManagedBlock()) {
                while (!queue.ready.isEmpty()) {
                    PendingPromotion<?> item = queue.ready.remove(queue.ready.size() - 1);
                    decrementPending();
                    item.run();
                }
                continue;
            }
            // Guarantee forward progress even if sustained >50ms modded ticks skipped between-tick pollTask().
            if (queue.drainedLastTick == 0) {
                long sliceStart = System.nanoTime();
                while (!queue.ready.isEmpty()) {
                    if (!runBestIfAllowed(queue, STARVATION_SLICE_NS, false)) {
                        break;
                    }
                    if (System.nanoTime() - sliceStart >= STARVATION_SLICE_NS) {
                        break;
                    }
                }
            } else {
                // Promote any chunk a fast-moving player just entered/approached without waiting for pollTask().
                drainAdjacentOnly(queue);
            }
        }
    }

    private static void drainAdjacentOnly(PacerQueue queue) {
        MoveTracker.PlayerMotion[] motions = activeMotionsFor(queue);
        if (motions.length == 0) {
            return;
        }
        for (int i = queue.ready.size() - 1; i >= 0; i--) {
            PendingPromotion<?> item = queue.ready.get(i);
            if (isNearMotions(item.chunkX, item.chunkZ, motions)) {
                removeSwapLast(queue.ready, i);
                long t0 = System.nanoTime();
                decrementPending();
                item.run();
                queue.spentNsThisTick += Math.max(0L, System.nanoTime() - t0);
                queue.drainedThisTick++;
                i = Math.min(i, queue.ready.size());
            }
        }
    }

    private static boolean runBestIfAllowed(PacerQueue queue, long budgetNs, boolean forceBypass) {
        MoveTracker.PlayerMotion[] motions = activeMotionsFor(queue);
        int bestIdx = selectBestIndex(queue.ready, motions);
        if (bestIdx < 0) {
            return false;
        }
        PendingPromotion<?> best = queue.ready.get(bestIdx);
        boolean nearPlayer = !forceBypass && isNearMotions(best.chunkX, best.chunkZ, motions);
        if (!forceBypass && !nearPlayer && queue.drainedThisTick > 0 && queue.spentNsThisTick >= budgetNs) {
            return false;
        }
        removeSwapLast(queue.ready, bestIdx);
        long t0 = System.nanoTime();
        decrementPending();
        best.run();
        queue.spentNsThisTick += Math.max(0L, System.nanoTime() - t0);
        queue.drainedThisTick++;
        return true;
    }

    private static int selectBestIndex(
            ArrayList<PendingPromotion<?>> list,
            MoveTracker.PlayerMotion[] motions
    ) {
        int n = list.size();
        if (n == 0) {
            return -1;
        }
        if (n == 1 || motions.length == 0) {
            return 0;
        }
        int bestIdx = 0;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            PendingPromotion<?> item = list.get(i);
            double score = Double.POSITIVE_INFINITY;
            for (int j = 0; j < motions.length; j++) {
                MoveTracker.PlayerMotion m = motions[j];
                if (m == null) {
                    continue;
                }
                int pcx = ((int) Math.floor(m.x)) >> 4;
                int pcz = ((int) Math.floor(m.z)) >> 4;
                double eff = LookAhead.computeEffectiveDistance(
                        pcx, pcz, m.vx, m.vz, m.lookX, m.lookZ, item.chunkX, item.chunkZ);
                if (eff < score) {
                    score = eff;
                }
            }
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
                if (bestScore <= 0.0D) {
                    break;
                }
            }
        }
        return bestIdx;
    }

    private static MoveTracker.PlayerMotion[] activeMotionsFor(PacerQueue queue) {
        MoveTracker tracker = ChunkEngine.get().velocityTracker();
        ServerLevel level = queue.level;
        if (level != null) {
            MoveTracker.PlayerMotion[] dimMotions =
                    tracker.getMotions(level.dimension().identifier().toString());
            if (dimMotions.length > 0) {
                return dimMotions;
            }
        } else if (!queue.ready.isEmpty()) {
            String dimKey = queue.ready.get(0).dimensionKey;
            if (dimKey != null) {
                MoveTracker.PlayerMotion[] dimMotions = tracker.getMotions(dimKey);
                if (dimMotions.length > 0) {
                    return dimMotions;
                }
            }
        }
        return tracker.getAnyActiveMotions();
    }

    private static boolean isNearAnyPlayer(String dimKey, int chunkX, int chunkZ) {
        MoveTracker tracker = ChunkEngine.get().velocityTracker();
        MoveTracker.PlayerMotion[] motions = dimKey != null
                ? tracker.getMotions(dimKey)
                : tracker.getAnyActiveMotions();
        if (motions.length == 0 && dimKey != null) {
            motions = tracker.getAnyActiveMotions();
        }
        return isNearMotions(chunkX, chunkZ, motions);
    }

    private static boolean isNearMotions(
            int chunkX,
            int chunkZ,
            MoveTracker.PlayerMotion[] motions
    ) {
        for (int i = 0; i < motions.length; i++) {
            MoveTracker.PlayerMotion m = motions[i];
            if (m == null) {
                continue;
            }
            int pcx = ((int) Math.floor(m.x)) >> 4;
            int pcz = ((int) Math.floor(m.z)) >> 4;
            if (Math.abs(chunkX - pcx) <= PLAYER_BYPASS_RADIUS
                    && Math.abs(chunkZ - pcz) <= PLAYER_BYPASS_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static void pullIngress(PacerQueue queue) {
        PendingPromotion<?> item;
        while ((item = queue.ingress.poll()) != null) {
            queue.ready.add(item);
        }
    }

    private static void advanceTickWindow(PacerQueue queue, long stamp) {
        if (stamp != queue.tickStamp) {
            queue.drainedLastTick = queue.tickStamp == Long.MIN_VALUE ? 1 : queue.drainedThisTick;
            queue.tickStamp = stamp;
            queue.spentNsThisTick = 0L;
            queue.drainedThisTick = 0;
        }
    }

    private static PendingPromotion<?> removeSwapLast(ArrayList<PendingPromotion<?>> list, int idx) {
        int last = list.size() - 1;
        PendingPromotion<?> removed = list.get(idx);
        if (idx != last) {
            list.set(idx, list.get(last));
        }
        list.remove(last);
        return removed;
    }

    private static void decrementPending() {
        while (true) {
            int cur = GLOBAL_PENDING.get();
            if (cur <= 0 || GLOBAL_PENDING.compareAndSet(cur, cur - 1)) {
                return;
            }
        }
    }

    private static void ensureHooksRegistered() {
        if (!HOOKS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            ServerTickEvents.START_SERVER_TICK.register(LoadPacer::onServerTickStart);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> flushAll());
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> flushAll());
        } catch (Throwable ignored) {
            // Fabric API lifecycle events are absent in headless unit tests.
        }
    }
}
