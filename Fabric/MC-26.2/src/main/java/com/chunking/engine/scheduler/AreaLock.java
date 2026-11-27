package com.chunking.engine.scheduler;

import com.chunking.engine.config.ModConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Lock-free zero-domino per-cell async area coordinator for {@code blockStateWriteRadius >= 1}
 * chunk stages with dynamic {@code mod (2*r + 1)} chromatic wave draining.
 *
 * <p>Radius-1 tasks use a dedicated zero-overhead 9-color ring; modded stages with
 * {@code writeRadius >= 2} lazily allocate their own {@code (2*r + 1)^2}-color band so tasks
 * drained from the same wave never collide on shared cells.
 */
public final class AreaLock {
    private static final int R1_COLOR_COUNT = 9;
    private static final int MAX_CHROMATIC_RADIUS = 16;

    private static final class RadiusBand {
        final int radius;
        final int colorCount;
        final ConcurrentLinkedQueue<AreaRequest<?>>[] queues;
        volatile int activeColor;
        final AtomicInteger queued = new AtomicInteger();

        @SuppressWarnings("unchecked")
        RadiusBand(int radius) {
            this.radius = radius;
            int period = (radius << 1) + 1;
            int count = period * period;
            this.colorCount = count;
            ConcurrentLinkedQueue<AreaRequest<?>>[] q = new ConcurrentLinkedQueue[count];
            for (int i = 0; i < count; i++) {
                q[i] = new ConcurrentLinkedQueue<>();
            }
            this.queues = q;
        }
    }

    private static final class AreaRequest<T> {
        final Long[] keys;
        final int color;
        final RadiusBand band;
        final Supplier<T> work;
        final Executor executor;
        final CompletableFuture<T> future;
        final AtomicBoolean wasPending = new AtomicBoolean(false);
        // Accessed exclusively by the single thread currently acquiring, parking, or releasing req.
        boolean scratchFlag;
        ArrayDeque<AreaRequest<?>> scratchWaiters;

        AreaRequest(
                Long[] keys,
                int color,
                RadiusBand band,
                Supplier<T> work,
                Executor executor,
                CompletableFuture<T> future
        ) {
            this.keys = keys;
            this.color = color;
            this.band = band;
            this.work = work;
            this.executor = executor;
            this.future = future;
        }
    }

    private static final class CellSlot {
        final AreaRequest<?> owner;
        ArrayDeque<AreaRequest<?>> waiters;

        CellSlot(AreaRequest<?> owner) {
            this.owner = owner;
        }
    }

    private final ConcurrentHashMap<Long, CellSlot> cellLocks = new ConcurrentHashMap<>(512);
    private final ConcurrentLinkedQueue<AreaRequest<?>>[] r1Queues;
    private volatile int activeR1Color;
    private volatile RadiusBand[] extraBands;
    private volatile int activeBandIdx;
    private final Object bandLock = new Object();
    private final AtomicInteger activeDispatched = new AtomicInteger();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger pumpWip = new AtomicInteger();
    private final AtomicLong immediateAdmits = new AtomicLong();
    private final AtomicLong deferredAdmits = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();

    @SuppressWarnings("unchecked")
    public AreaLock() {
        ConcurrentLinkedQueue<AreaRequest<?>>[] queues = new ConcurrentLinkedQueue[R1_COLOR_COUNT];
        for (int i = 0; i < R1_COLOR_COUNT; i++) {
            queues[i] = new ConcurrentLinkedQueue<>();
        }
        this.r1Queues = queues;
    }

    public static int chromaticColor(int cx, int cz) {
        return Math.floorMod(cx, 3) + 3 * Math.floorMod(cz, 3);
    }

    public static int chromaticColor(int cx, int cz, int writeRadius) {
        int r = Math.max(1, Math.min(writeRadius, MAX_CHROMATIC_RADIUS));
        if (r == 1) {
            return Math.floorMod(cx, 3) + 3 * Math.floorMod(cz, 3);
        }
        int period = (r << 1) + 1;
        return Math.floorMod(cx, period) + period * Math.floorMod(cz, period);
    }

    public int activeDispatchedCount() {
        return this.activeDispatched.get();
    }

    private RadiusBand getOrCreateBand(int radius) {
        int r = Math.min(radius, MAX_CHROMATIC_RADIUS);
        RadiusBand[] bands = this.extraBands;
        if (bands != null) {
            for (int i = 0; i < bands.length; i++) {
                if (bands[i].radius == r) {
                    return bands[i];
                }
            }
        }
        synchronized (this.bandLock) {
            bands = this.extraBands;
            if (bands != null) {
                for (int i = 0; i < bands.length; i++) {
                    if (bands[i].radius == r) {
                        return bands[i];
                    }
                }
                RadiusBand created = new RadiusBand(r);
                RadiusBand[] next = Arrays.copyOf(bands, bands.length + 1);
                next[bands.length] = created;
                this.extraBands = next;
                return created;
            }
            RadiusBand created = new RadiusBand(r);
            this.extraBands = new RadiusBand[]{created};
            return created;
        }
    }

    private static int maxConcurrentFeatures() {
        int workers = Math.max(1, ModConfig.WORLDGEN_WORKERS);
        int limit = TaskQueue.hasQueuedWork()
                ? Math.max(1, workers - TaskQueue.reservedUpstreamWorkers(workers))
                : workers;
        FrameGuard gov = FrameGuard.get();
        if (gov.isRunning() && gov.hasActiveClientFrames()) {
            limit = Math.min(limit, Math.max(1, gov.inFlightCap(workers)));
        }
        return limit;
    }

    private void enqueueReady(AreaRequest<?> req) {
        RadiusBand band = req.band;
        if (band == null) {
            this.r1Queues[req.color].offer(req);
            return;
        }
        band.queues[req.color].offer(req);
        band.queued.incrementAndGet();
    }

    private boolean hasAnyReady() {
        for (int i = 0; i < R1_COLOR_COUNT; i++) {
            if (!this.r1Queues[i].isEmpty()) {
                return true;
            }
        }
        RadiusBand[] extras = this.extraBands;
        if (extras != null) {
            for (int i = 0; i < extras.length; i++) {
                if (extras[i].queued.get() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private AreaRequest<?> pollR1() {
        int start = this.activeR1Color;
        for (int offset = 0; offset < R1_COLOR_COUNT; offset++) {
            int c = (start + offset) % R1_COLOR_COUNT;
            AreaRequest<?> req = this.r1Queues[c].poll();
            if (req != null) {
                this.activeR1Color = c;
                return req;
            }
        }
        return null;
    }

    private static AreaRequest<?> pollBand(RadiusBand band) {
        if (band.queued.get() <= 0) {
            return null;
        }
        int count = band.colorCount;
        int start = band.activeColor;
        for (int offset = 0; offset < count; offset++) {
            int c = (start + offset) % count;
            AreaRequest<?> req = band.queues[c].poll();
            if (req != null) {
                band.queued.decrementAndGet();
                band.activeColor = c;
                return req;
            }
        }
        return null;
    }

    private AreaRequest<?> pollNextChromatic() {
        RadiusBand[] extras = this.extraBands;
        if (extras == null) {
            return pollR1();
        }
        int totalBands = extras.length + 1;
        int startBand = this.activeBandIdx % totalBands;
        for (int off = 0; off < totalBands; off++) {
            int bIdx = (startBand + off) % totalBands;
            AreaRequest<?> req = (bIdx == 0) ? pollR1() : pollBand(extras[bIdx - 1]);
            if (req != null) {
                this.activeBandIdx = bIdx;
                return req;
            }
        }
        return null;
    }

    private boolean tryClaimPermit() {
        while (true) {
            int cur = this.activeDispatched.get();
            int limit = maxConcurrentFeatures();
            if (cur >= limit) {
                return false;
            }
            if (this.activeDispatched.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    // Box keys once per request so containsKey, acquire, park, and release reuse the same Long instances.
    private static Long[] buildCanonicalAreaKeys(int cx, int cz, int r) {
        int side = (r << 1) + 1;
        Long[] keys = new Long[side * side];
        int k = 0;
        for (int x = cx - r; x <= cx + r; x++) {
            long hi = ((long) x) << 32;
            for (int z = cz - r; z <= cz + r; z++) {
                keys[k++] = Long.valueOf(hi | (z & 0xFFFFFFFFL));
            }
        }
        return keys;
    }

    public <T> CompletableFuture<T> executeAsync(int chunkX, int chunkZ, Supplier<T> work, Executor executor) {
        return executeAsync(chunkX, chunkZ, 1, work, executor);
    }

    public <T> CompletableFuture<T> executeAsync(
            int chunkX,
            int chunkZ,
            int writeRadius,
            Supplier<T> work,
            Executor executor
    ) {
        int r = Math.max(1, writeRadius);
        RadiusBand band = (r == 1) ? null : getOrCreateBand(r);
        int color = (r == 1) ? chromaticColor(chunkX, chunkZ) : chromaticColor(chunkX, chunkZ, r);
        CompletableFuture<T> future = new CompletableFuture<>();
        AreaRequest<T> req = new AreaRequest<>(
                buildCanonicalAreaKeys(chunkX, chunkZ, r),
                color,
                band,
                work,
                executor,
                future
        );
        if (tryClaimPermit()) {
            if (tryAcquireAllOrPark(req)) {
                this.immediateAdmits.incrementAndGet();
                dispatchOwned(req);
            } else {
                this.activeDispatched.decrementAndGet();
                triggerPump();
            }
        } else {
            if (req.wasPending.compareAndSet(false, true)) {
                this.pendingTasks.incrementAndGet();
                this.deferredAdmits.incrementAndGet();
            }
            enqueueReady(req);
            triggerPump();
        }
        return future;
    }

    private boolean tryAcquireAllOrPark(AreaRequest<?> req) {
        Long[] keys = req.keys;
        for (int attempt = 0; attempt < 8; attempt++) {
            Long preBlocker = null;
            for (int i = 0; i < keys.length; i++) {
                if (this.cellLocks.containsKey(keys[i])) {
                    preBlocker = keys[i];
                    break;
                }
            }
            if (preBlocker != null) {
                if (parkOnBlocker(req, preBlocker)) {
                    return false;
                }
                continue;
            }

            int acquiredCount = 0;
            Long blockerKey = null;
            for (int i = 0; i < keys.length; i++) {
                Long key = keys[i];
                req.scratchFlag = false;
                this.cellLocks.compute(key, (k, slot) -> {
                    if (slot == null) {
                        req.scratchFlag = true;
                        return new CellSlot(req);
                    }
                    return slot;
                });
                if (req.scratchFlag) {
                    acquiredCount++;
                } else {
                    blockerKey = key;
                    break;
                }
            }
            if (blockerKey == null) {
                return true;
            }
            if (acquiredCount > 0) {
                releaseCells(req, acquiredCount);
            }
            if (parkOnBlocker(req, blockerKey)) {
                return false;
            }
        }

        if (req.wasPending.compareAndSet(false, true)) {
            this.pendingTasks.incrementAndGet();
            this.deferredAdmits.incrementAndGet();
        }
        enqueueReady(req);
        return false;
    }

    private boolean parkOnBlocker(AreaRequest<?> req, Long blockerKey) {
        req.scratchFlag = false;
        this.cellLocks.compute(blockerKey, (k, slot) -> {
            if (slot == null) {
                return null;
            }
            if (slot.waiters == null) {
                slot.waiters = new ArrayDeque<>(4);
            }
            if (req.wasPending.compareAndSet(false, true)) {
                this.pendingTasks.incrementAndGet();
                this.deferredAdmits.incrementAndGet();
            }
            slot.waiters.addLast(req);
            req.scratchFlag = true;
            return slot;
        });
        return req.scratchFlag;
    }

    private void releaseCells(AreaRequest<?> req, int count) {
        Long[] keys = req.keys;
        ArrayDeque<AreaRequest<?>> firstDrained = null;
        ArrayList<AreaRequest<?>> extraWoken = null;
        for (int i = 0; i < count; i++) {
            req.scratchWaiters = null;
            this.cellLocks.compute(keys[i], (k, slot) -> {
                if (slot == null || slot.owner != req) {
                    return slot;
                }
                req.scratchWaiters = slot.waiters;
                return null;
            });
            ArrayDeque<AreaRequest<?>> drained = req.scratchWaiters;
            if (drained != null && !drained.isEmpty()) {
                if (firstDrained == null) {
                    firstDrained = drained;
                } else {
                    if (extraWoken == null) {
                        extraWoken = new ArrayList<>(drained.size());
                    }
                    while (!drained.isEmpty()) {
                        extraWoken.add(drained.pollFirst());
                    }
                }
            }
        }
        req.scratchWaiters = null;
        if (firstDrained != null) {
            while (!firstDrained.isEmpty()) {
                enqueueReady(firstDrained.pollFirst());
            }
        }
        if (extraWoken != null) {
            for (int i = 0; i < extraWoken.size(); i++) {
                enqueueReady(extraWoken.get(i));
            }
        }
    }

    private <T> void dispatchOwned(AreaRequest<T> req) {
        try {
            req.executor.execute(() -> {
                try {
                    T result = req.work.get();
                    this.completedTasks.incrementAndGet();
                    releaseCellsAndPump(req);
                    req.future.complete(result);
                } catch (Throwable t) {
                    this.completedTasks.incrementAndGet();
                    releaseCellsAndPump(req);
                    req.future.completeExceptionally(t);
                }
            });
        } catch (Throwable submitError) {
            this.completedTasks.incrementAndGet();
            releaseCellsAndPump(req);
            req.future.completeExceptionally(submitError);
        }
    }

    public void pumpPending() {
        if (hasAnyReady()) {
            triggerPump();
        }
    }

    private void triggerPump() {
        if (this.pumpWip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (;;) {
            while (true) {
                int limit = maxConcurrentFeatures();
                int cur = this.activeDispatched.get();
                if (cur >= limit) {
                    break;
                }
                AreaRequest<?> next = pollNextChromatic();
                if (next == null) {
                    break;
                }
                if (!this.activeDispatched.compareAndSet(cur, cur + 1)) {
                    enqueueReady(next);
                    continue;
                }
                if (tryAcquireAllOrPark(next)) {
                    if (next.wasPending.compareAndSet(true, false)) {
                        this.pendingTasks.decrementAndGet();
                    }
                    dispatchOwned(next);
                } else {
                    this.activeDispatched.decrementAndGet();
                    if (next.band == null) {
                        this.activeR1Color = (next.color + 1) % R1_COLOR_COUNT;
                    } else {
                        next.band.activeColor = (next.color + 1) % next.band.colorCount;
                    }
                }
            }
            missed = this.pumpWip.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    private void releaseCellsAndPump(AreaRequest<?> req) {
        releaseCells(req, req.keys.length);
        this.activeDispatched.decrementAndGet();
        triggerPump();
        TaskQueue.onFeatureReleased(req.executor);
    }

    public int activeOwnedChunkCells() {
        return this.cellLocks.size();
    }

    public int pendingCount() {
        return Math.max(0, this.pendingTasks.get());
    }

    public long immediateAdmits() {
        return this.immediateAdmits.get();
    }

    public long deferredAdmits() {
        return this.deferredAdmits.get();
    }

    public long completedTasks() {
        return this.completedTasks.get();
    }
}
