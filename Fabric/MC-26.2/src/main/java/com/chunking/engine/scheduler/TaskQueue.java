package com.chunking.engine.scheduler;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 64-Bucket lock-free priority scheduler for parallel worldgen stages with
 * full-bucket dynamic position/velocity/camera-frustum re-bucketing, stage-completion bias,
 * and coordinated worker-slot sharing with {@link AreaLock} for 2-8+ workers.
 */
public final class TaskQueue {
    private static final int BUCKET_COUNT = 64;
    private static final ThreadLocal<CompletableFuture<?>> CAPTURED_STAGE_FUTURE = new ThreadLocal<>();

    private static final class PrioritizedTask<T> implements Runnable {
        final int chunkX;
        final int chunkZ;
        final int statusRank;
        final Supplier<T> work;
        final CompletableFuture<T> future;
        Executor pool;

        PrioritizedTask(int chunkX, int chunkZ, int statusRank, Supplier<T> work, CompletableFuture<T> future) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.statusRank = statusRank;
            this.work = work;
            this.future = future;
        }

        @Override
        public void run() {
            Executor exec = this.pool;
            this.pool = null;
            try {
                T result = this.work.get();
                this.future.complete(result);
            } catch (Throwable t) {
                this.future.completeExceptionally(t);
            } finally {
                IN_FLIGHT.decrementAndGet();
                if (exec != null) {
                    pump(exec);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<PrioritizedTask<?>>[] BUCKETS =
            new ConcurrentLinkedQueue[BUCKET_COUNT];
    private static final AtomicLong NON_EMPTY_MASK = new AtomicLong(0L);
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger QUEUED_COUNT = new AtomicInteger();
    private static final AtomicInteger PUMP_WIP = new AtomicInteger();
    private static final AtomicBoolean REBALANCING = new AtomicBoolean(false);

    // Single-owner scratch buffers guarded exclusively by REBALANCING CAS
    private static final ArrayList<PrioritizedTask<?>> REBALANCE_STAY = new ArrayList<>(64);
    private static final ArrayList<PrioritizedTask<?>> REBALANCE_MOVED = new ArrayList<>(128);
    private static int[] rebalanceMovedBuckets = new int[128];

    private static volatile int spawnAnchorX = Integer.MIN_VALUE;
    private static volatile int spawnAnchorZ = Integer.MIN_VALUE;
    private static volatile int lastRebalanceCx = Integer.MIN_VALUE;
    private static volatile int lastRebalanceCz = Integer.MIN_VALUE;
    private static volatile int lastRebalanceDirCode = Integer.MIN_VALUE;
    private static volatile long lastRebalanceNs = 0L;

    static {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            BUCKETS[i] = new ConcurrentLinkedQueue<>();
        }
    }

    private TaskQueue() {}

    public static void resetSession() {
        spawnAnchorX = Integer.MIN_VALUE;
        spawnAnchorZ = Integer.MIN_VALUE;
        lastRebalanceCx = Integer.MIN_VALUE;
        lastRebalanceCz = Integer.MIN_VALUE;
        lastRebalanceDirCode = Integer.MIN_VALUE;
        lastRebalanceNs = 0L;
    }

    public static void captureStageFuture(CompletableFuture<?> future) {
        CAPTURED_STAGE_FUTURE.set(future);
    }

    public static CompletableFuture<?> pollCapturedStageFuture() {
        CompletableFuture<?> f = CAPTURED_STAGE_FUTURE.get();
        if (f != null) {
            CAPTURED_STAGE_FUTURE.remove();
        }
        return f;
    }

    public static boolean hasQueuedWork() {
        return NON_EMPTY_MASK.get() != 0L && QUEUED_COUNT.get() > 0;
    }

    public static int inFlightCount() {
        return IN_FLIGHT.get();
    }

    public static int reservedUpstreamWorkers(int workers) {
        if (workers <= 1) {
            return 0;
        }
        if (workers <= 4) {
            return 1;
        }
        if (workers <= 8) {
            return 2;
        }
        if (workers <= 14) {
            return 3;
        }
        return Math.max(4, workers / 4);
    }

    private static void setBucketBit(int idx) {
        long bit = 1L << idx;
        while (true) {
            long cur = NON_EMPTY_MASK.get();
            long next = cur | bit;
            if (cur == next || NON_EMPTY_MASK.compareAndSet(cur, next)) {
                return;
            }
        }
    }

    private static void clearBucketBit(int idx) {
        long bit = 1L << idx;
        while (true) {
            long cur = NON_EMPTY_MASK.get();
            long next = cur & ~bit;
            if (cur == next || NON_EMPTY_MASK.compareAndSet(cur, next)) {
                return;
            }
        }
    }

    private static void decrementQueuedCount() {
        while (true) {
            int cur = QUEUED_COUNT.get();
            if (cur <= 0) {
                return;
            }
            if (QUEUED_COUNT.compareAndSet(cur, cur - 1)) {
                return;
            }
        }
    }

    public static int computeBucket(int chunkX, int chunkZ, int statusRank) {
        MoveTracker.PlayerMotion[] motions =
                ChunkEngine.get().velocityTracker().getAnyActiveMotions();
        double bestEff = 63.0D;
        if (motions == null || motions.length == 0) {
            int ax = spawnAnchorX;
            int az = spawnAnchorZ;
            if (ax == Integer.MIN_VALUE || Math.abs((long) chunkX - ax) > 128L || Math.abs((long) chunkZ - az) > 128L) {
                spawnAnchorX = ax = chunkX;
                spawnAnchorZ = az = chunkZ;
            }
            long dx = (long) chunkX - (long) ax;
            long dz = (long) chunkZ - (long) az;
            bestEff = (int) Math.min(63L, Math.max(Math.abs(dx), Math.abs(dz)));
        } else {
            for (int i = 0; i < motions.length; i++) {
                MoveTracker.PlayerMotion m = motions[i];
                if (m == null) continue;
                int pcx = ((int) Math.floor(m.x)) >> 4;
                int pcz = ((int) Math.floor(m.z)) >> 4;
                long dx = (long) chunkX - (long) pcx;
                long dz = (long) chunkZ - (long) pcz;
                if (dx * dx + dz * dz > 16384L) continue;
                double eff = LookAhead.computeEffectiveDistance(
                        pcx, pcz, m.vx, m.vz, m.lookX, m.lookZ, chunkX, chunkZ);
                if (eff < bestEff) {
                    bestEff = eff;
                }
            }
        }
        int ringBucket = bestEff <= 24.0D
                ? Math.max(0, (int) Math.round(bestEff)) * 2
                : Math.min(55, 48 + (int) Math.round((bestEff - 24.0D) * 0.175D));
        int bucket = ringBucket + Math.max(0, 7 - statusRank);
        return Math.max(0, Math.min(BUCKET_COUNT - 1, bucket));
    }

    /** Re-evaluates queued tasks off the server thread with a 400 ms / 2-chunk debounce. */
    public static void onPlayerMotionsUpdated(MoveTracker.PlayerMotion[] motions) {
        if (motions == null || motions.length == 0 || NON_EMPTY_MASK.get() == 0L || QUEUED_COUNT.get() < 8) {
            return;
        }
        MoveTracker.PlayerMotion primary = motions[0];
        if (primary == null) {
            return;
        }
        long now = System.nanoTime();
        if (lastRebalanceCx != Integer.MIN_VALUE && (now - lastRebalanceNs) < 400_000_000L) {
            return;
        }
        int pcx = ((int) Math.floor(primary.x)) >> 4;
        int pcz = ((int) Math.floor(primary.z)) >> 4;
        int lookCode = (((int) Math.round(primary.lookX * 3.0D)) << 8) ^ ((int) Math.round(primary.lookZ * 3.0D) & 0xFF);
        int velCode = primary.speedXZ >= LookAhead.HIGH_SPEED_THRESHOLD
                ? ((((int) Math.round(primary.dirX * 4.0D)) << 4) ^ ((int) Math.round(primary.dirZ * 4.0D) & 0xF))
                : 0;
        int dirCode = (lookCode << 12) ^ velCode;
        if (pcx == lastRebalanceCx && pcz == lastRebalanceCz && dirCode == lastRebalanceDirCode) {
            return;
        }
        if (lastRebalanceCx != Integer.MIN_VALUE
                && Math.abs(pcx - lastRebalanceCx) < 2
                && Math.abs(pcz - lastRebalanceCz) < 2) {
            return;
        }
        lastRebalanceCx = pcx;
        lastRebalanceCz = pcz;
        lastRebalanceDirCode = dirCode;
        lastRebalanceNs = now;

        if (!REBALANCING.compareAndSet(false, true)) {
            return;
        }
        try {
            ChunkEngine.get().workerPool().executor().execute(() -> {
                try {
                    rebalanceBuckets();
                } finally {
                    REBALANCING.set(false);
                }
            });
        } catch (Throwable ignored) {
            REBALANCING.set(false);
        }
    }

    private static void rebalanceBuckets() {
        ArrayList<PrioritizedTask<?>> stayTasks = REBALANCE_STAY;
        ArrayList<PrioritizedTask<?>> movedTasks = REBALANCE_MOVED;
        int[] movedBuckets = rebalanceMovedBuckets;
        int movedCount = 0;
        long touchedMask = 0L;
        stayTasks.clear();
        movedTasks.clear();
        try {
            int perBucketBudget = Math.max(32, Math.min(512, QUEUED_COUNT.get() + 32));
            long maskSnap = NON_EMPTY_MASK.get();
            while (maskSnap != 0L) {
                int b = Long.numberOfTrailingZeros(maskSnap);
                maskSnap &= (maskSnap - 1L);
                ConcurrentLinkedQueue<PrioritizedTask<?>> q = BUCKETS[b];
                stayTasks.clear();
                for (int i = 0; i < perBucketBudget; i++) {
                    PrioritizedTask<?> task = q.poll();
                    if (task == null) {
                        break;
                    }
                    int target = computeBucket(task.chunkX, task.chunkZ, task.statusRank);
                    if (target == b) {
                        stayTasks.add(task);
                    } else {
                        if (movedCount == movedBuckets.length) {
                            movedBuckets = Arrays.copyOf(movedBuckets, movedBuckets.length << 1);
                            rebalanceMovedBuckets = movedBuckets;
                        }
                        movedTasks.add(task);
                        movedBuckets[movedCount++] = target;
                    }
                }
                if (!stayTasks.isEmpty()) {
                    for (int i = 0, n = stayTasks.size(); i < n; i++) {
                        q.offer(stayTasks.get(i));
                    }
                    stayTasks.clear();
                    setBucketBit(b);
                } else {
                    touchedMask |= (1L << b);
                }
            }
            for (int i = 0; i < movedCount; i++) {
                int target = movedBuckets[i];
                BUCKETS[target].offer(movedTasks.get(i));
                setBucketBit(target);
            }
            movedCount = 0;
            movedTasks.clear();
            while (touchedMask != 0L) {
                int b = Long.numberOfTrailingZeros(touchedMask);
                touchedMask &= (touchedMask - 1L);
                ConcurrentLinkedQueue<PrioritizedTask<?>> q = BUCKETS[b];
                if (q.isEmpty()) {
                    clearBucketBitIfEmpty(b, q);
                }
            }
        } finally {
            for (int i = 0, n = stayTasks.size(); i < n; i++) {
                PrioritizedTask<?> t = stayTasks.get(i);
                int target = Math.max(0, Math.min(BUCKET_COUNT - 1, computeBucket(t.chunkX, t.chunkZ, t.statusRank)));
                BUCKETS[target].offer(t);
                setBucketBit(target);
            }
            for (int i = 0; i < movedCount; i++) {
                int target = movedBuckets[i];
                BUCKETS[target].offer(movedTasks.get(i));
                setBucketBit(target);
            }
            stayTasks.clear();
            movedTasks.clear();
            if (rebalanceMovedBuckets.length > 4096) {
                rebalanceMovedBuckets = new int[256];
            }
            if (NON_EMPTY_MASK.get() != 0L) {
                pump(ChunkEngine.get().workerPool().executor());
            }
        }
    }

    private static void clearBucketBitIfEmpty(int idx, ConcurrentLinkedQueue<PrioritizedTask<?>> q) {
        clearBucketBit(idx);
        if (!q.isEmpty()) {
            setBucketBit(idx);
        }
    }

    public static <T> CompletableFuture<T> submit(
            int chunkX,
            int chunkZ,
            int statusRank,
            Supplier<T> work,
            Executor pool
    ) {
        if (NON_EMPTY_MASK.get() == 0L) {
            int cap = effectiveUpstreamCap();
            int cur = IN_FLIGHT.get();
            if (cur < cap && IN_FLIGHT.compareAndSet(cur, cur + 1)) {
                CompletableFuture<T> future = new CompletableFuture<>();
                try {
                    pool.execute(() -> {
                        try {
                            T result = work.get();
                            future.complete(result);
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        } finally {
                            IN_FLIGHT.decrementAndGet();
                            pump(pool);
                        }
                    });
                } catch (Throwable submitError) {
                    IN_FLIGHT.decrementAndGet();
                    future.completeExceptionally(submitError);
                }
                return future;
            }
        }
        int bucket = computeBucket(chunkX, chunkZ, statusRank);
        CompletableFuture<T> future = new CompletableFuture<>();
        QUEUED_COUNT.incrementAndGet();
        BUCKETS[bucket].offer(new PrioritizedTask<>(chunkX, chunkZ, statusRank, work, future));
        setBucketBit(bucket);
        pump(pool);
        return future;
    }

    private static PrioritizedTask<?> pollHighestPriority() {
        while (true) {
            long mask = NON_EMPTY_MASK.get();
            if (mask == 0L) {
                return null;
            }
            int idx = Long.numberOfTrailingZeros(mask);
            ConcurrentLinkedQueue<PrioritizedTask<?>> q = BUCKETS[idx];
            PrioritizedTask<?> task = q.poll();
            if (task != null) {
                decrementQueuedCount();
                if (q.isEmpty()) {
                    clearBucketBitIfEmpty(idx, q);
                }
                return task;
            }
            clearBucketBitIfEmpty(idx, q);
        }
    }

    private static int effectiveUpstreamCap() {
        int workers = Math.max(1, ModConfig.WORLDGEN_WORKERS);
        int activeFeatures = ChunkEngine.get().featureLock().activeDispatchedCount();
        FrameGuard gov = FrameGuard.get();
        int govCap = gov.inFlightCap(workers);
        if (activeFeatures == 0) {
            return govCap;
        }
        int reserved = Math.max(1, reservedUpstreamWorkers(workers));
        int cap = Math.max(reserved, workers - activeFeatures + reserved);
        return gov.level() == FrameGuard.Level.SEVERE ? Math.min(govCap, cap) : cap;
    }

    public static void onFeatureReleased(Executor pool) {
        if (pool != null && hasQueuedWork()) {
            pump(pool);
        }
    }

    private static void pump(Executor pool) {
        if (PUMP_WIP.getAndIncrement() != 0) {
            return;
        }
        try {
            do {
                while (true) {
                    int cap = effectiveUpstreamCap();
                    int cur = IN_FLIGHT.get();
                    if (cur >= cap) {
                        break;
                    }
                    if (!IN_FLIGHT.compareAndSet(cur, cur + 1)) {
                        continue;
                    }
                    PrioritizedTask<?> next = pollHighestPriority();
                    if (next == null) {
                        IN_FLIGHT.decrementAndGet();
                        ChunkEngine.get().featureLock().pumpPending();
                        break;
                    }
                    dispatch(next, pool);
                }
            } while (PUMP_WIP.decrementAndGet() > 0);
        } catch (Throwable t) {
            PUMP_WIP.set(0);
            throw t;
        }
    }

    private static <T> void dispatch(PrioritizedTask<T> task, Executor pool) {
        task.pool = pool;
        try {
            pool.execute(task);
        } catch (Throwable submitError) {
            task.pool = null;
            IN_FLIGHT.decrementAndGet();
            task.future.completeExceptionally(submitError);
        }
    }
}
