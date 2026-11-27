package com.chunking.engine.scheduler;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Closed-loop frame-time, server-tick, and GC-headroom governor.
 *
 * <p>On both dedicated servers and live clients ({@code minWorkers == maxWorkers}), all configured
 * worldgen worker threads stay active while in-flight task caps, chunk-send rates, and client packet
 * budgets adapt smoothly under frame, server-tick, or GC pressure so 1% and 0.1% low FPS stay smooth.
 *
 * <p>Server MSPT tracking combines an asymmetric EWMA (fast attack, smooth decay) with per-interval
 * peak-hold and Schmitt-trigger hysteresis, reacting immediately inside {@link #onServerTick(double)}
 * to any single &gt;35 ms tick spike while preventing full-throttle / hard-brake oscillation.
 */
public final class FrameGuard {
    public enum Level { CALM, MILD, SEVERE }

    private static final FrameGuard INSTANCE = new FrameGuard();

    public static FrameGuard get() { return INSTANCE; }

        static final long INTERVAL_MS = 100L;
    static final float MIN_SEND_RATE = 6.0F;
    static final float LIVE_MIN_SEND_RATE = 24.0F;
    static final float MAX_LOCAL_SEND_RATE = 64.0F;
    static final float MAX_REMOTE_SEND_RATE = 48.0F;

    static final double TICK_MILD_MS = 35.0D;
    static final double TICK_SEVERE_MS = 44.0D;
    static final double TICK_CALM_EXIT_MS = 29.0D;
    static final double TICK_SEVERE_EXIT_MS = 37.0D;
    static final double SPIKE_MILD = 0.04D;
    static final double SPIKE_SEVERE = 0.12D;
    static final double GC_MILD = 0.06D;   // >6% of wall time in GC pauses
    static final double GC_SEVERE = 0.20D;
    static final double ALLOC_MILD_MB_S =
            computeAllocMildThresholdMbPerSec(Runtime.getRuntime().maxMemory() / (1024L * 1024L));
    static final double ALLOC_SEVERE_MB_S = ALLOC_MILD_MB_S * 1.8D;

    private static final long FRAME_STALE_NS = 1_500_000_000L;
    private static final com.sun.management.ThreadMXBean THREAD_ALLOC_BEAN = resolveThreadAllocBean();

    private static com.sun.management.ThreadMXBean resolveThreadAllocBean() {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sunBean
                    && sunBean.isThreadAllocatedMemorySupported()) {
                if (!sunBean.isThreadAllocatedMemoryEnabled()) {
                    try {
                        sunBean.setThreadAllocatedMemoryEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
                if (sunBean.isThreadAllocatedMemoryEnabled()) {
                    return sunBean;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static double computeAllocMildThresholdMbPerSec(long maxHeapMb) {
        long heapMb = Math.max(1024L, maxHeapMb);
        return Math.max(256.0D, Math.min(1024.0D, heapMb * 0.09D));
    }

        private volatile long lastFrameNs;
    private volatile long renderedFrameCount;
    private volatile double lastFrameDtMs = 16.7D;
    private volatile int initialWorldLoadFrames;
    private volatile long initialWorldLoadUntilNs;
    private volatile int hitchCooldownFrames;
    private volatile long hitchCooldownUntilNs;
    private volatile double frameEwmaMs = 16.7D;
    private volatile double baselineMs = 16.7D;
    private volatile double spikeRate;
    private static final double WINDOW_MS = 10_000.0D;
    private double windowAccumMs;
    private double windowMinMs = Double.MAX_VALUE;
    private double prevWindowMinMs = Double.MAX_VALUE;
    private int warmupFrames;

        private volatile double serverTickMs;
    private volatile double serverTickEwmaMs;
    private volatile double serverTickPeakMs;
    private volatile int serverHitchTicks;
    private volatile int clientWorldSections = 24;

        private volatile float sendRate = MAX_LOCAL_SEND_RATE;
    private volatile Level effective = Level.CALM;
    private volatile int targetWorkers;

        private int maxWorkers;
    private int minWorkers;
    private float minSendRateFloor = MIN_SEND_RATE;
    private int calmIntervals;
    private int mildIntervals;
    private long lastGcPauseMs;
    private long lastGcWallNs;
    private volatile double lastGcFraction;
    private volatile double allocRateMbPerSec;
    private volatile double allocEwmaMbPerSec;
    private long[] lastAllocThreadIds;
    private long lastAllocTotalBytes = -1L;
    private long lastAllocWallNs;
    private volatile boolean running;
    private volatile boolean testCapOverride;
    private ScheduledExecutorService executor;
    private Consumer<String> log;
    private long lastLogNs;
    private final List<GarbageCollectorMXBean> gcBeans = new ArrayList<>();
    private final Object controlLock = new Object();

    private FrameGuard() {}

    /** Render thread, once per rendered frame. O(1), allocation-free. */
    public void onFrame() {
        long now = System.nanoTime();
        long prev = this.lastFrameNs;
        this.lastFrameNs = now;
        this.renderedFrameCount++;
        if (prev == 0L) {
            return;
        }
        double dtMs = (now - prev) / 1_000_000.0D;
        if (dtMs <= 0.0D) {
            return;
        }
        recordFrameMs(dtMs);
    }

    /** Visible for tests: feed a frame duration directly. */
    void recordFrameMs(double dtMs) {
        this.lastFrameDtMs = dtMs;
        double clamped = Math.min(dtMs, 250.0D);
        double ewma = this.frameEwmaMs + 0.15D * (clamped - this.frameEwmaMs);
        this.frameEwmaMs = ewma;
        if (this.initialWorldLoadFrames > 0) {
            this.initialWorldLoadFrames--;
        }

        if (this.warmupFrames < 30) {
            this.warmupFrames++;
            this.baselineMs = Math.max(4.0D, ewma);
        } else {
            this.windowMinMs = Math.min(this.windowMinMs, ewma);
            this.windowAccumMs += clamped;
            if (this.windowAccumMs >= WINDOW_MS) {
                this.prevWindowMinMs = this.windowMinMs;
                this.windowMinMs = Double.MAX_VALUE;
                this.windowAccumMs = 0.0D;
            }
            this.baselineMs = Math.max(4.0D, Math.min(this.windowMinMs, this.prevWindowMinMs));
            if (this.baselineMs == Double.MAX_VALUE) {
                this.baselineMs = Math.max(4.0D, ewma);
            }
        }

        if (this.warmupFrames >= 10 && dtMs > Math.max(20.0D, this.baselineMs * 1.65D + 3.0D)) {
            this.hitchCooldownFrames = 6;
            this.hitchCooldownUntilNs = System.nanoTime() + 140_000_000L;
        } else if (this.hitchCooldownFrames > 0) {
            this.hitchCooldownFrames--;
        }

        double spikeThreshold = Math.max(2.0D * this.baselineMs, this.baselineMs + 16.0D);
        double hit = dtMs > spikeThreshold ? 1.0D : 0.0D;
        this.spikeRate += 0.03D * (hit - this.spikeRate);
    }

    /** Server thread, once per level tick. Reacts immediately to any single &gt;35 ms spike. */
    public void onServerTick(double effectiveTickMs) {
        double clamped = Math.max(0.0D, Math.min(effectiveTickMs, 250.0D));
        synchronized (this.controlLock) {
            this.serverTickMs = clamped;
            double prevEwma = this.serverTickEwmaMs;
            double alpha = clamped > prevEwma ? 0.35D : 0.12D;
            this.serverTickEwmaMs = prevEwma + alpha * (clamped - prevEwma);
            if (clamped > this.serverTickPeakMs) {
                this.serverTickPeakMs = clamped;
            }
            if (clamped > TICK_SEVERE_MS) {
                int hold = clamped > 55.0D ? 16 : 10;
                this.serverHitchTicks = Math.max(this.serverHitchTicks, hold);
                this.calmIntervals = 0;
                this.mildIntervals = 0;
                this.effective = Level.SEVERE;
                float cur = this.sendRate;
                float braked = Math.max(MIN_SEND_RATE, cur * 0.72F);
                if (braked < cur) {
                    this.sendRate = braked;
                }
            } else if (clamped > TICK_MILD_MS) {
                this.serverHitchTicks = Math.max(this.serverHitchTicks, 5);
                this.calmIntervals = 0;
                if (this.effective == Level.CALM) {
                    this.effective = Level.MILD;
                }
            } else if (clamped <= TICK_CALM_EXIT_MS && this.serverHitchTicks > 0) {
                this.serverHitchTicks--;
            }
        }
    }

    /**
     * Mid-tick fast spike check from {@code ServerChunkCache.tick} so a sudden chunk-tick spike
     * escalates pressure before the full server tick completes.
     */
    public void onServerTickSpikeCheck(double tickMs) {
        if (tickMs > TICK_SEVERE_MS) {
            onServerTick(tickMs);
        } else if (tickMs > TICK_MILD_MS) {
            synchronized (this.controlLock) {
                if (tickMs > this.serverTickPeakMs) {
                    this.serverTickPeakMs = tickMs;
                }
                this.serverHitchTicks = Math.max(this.serverHitchTicks, 4);
                if (this.effective == Level.CALM) {
                    this.effective = Level.MILD;
                }
            }
        }
    }

    public boolean isServerTickPressured() {
        return this.serverHitchTicks > 0
                || this.serverTickMs > 38.0D
                || this.serverTickEwmaMs > 36.0D;
    }

    public double serverTickMs() { return this.serverTickMs; }
    public double serverTickEwmaMs() { return this.serverTickEwmaMs; }

    public void recordClientWorldSections(int sections) {
        int clamped = Math.max(8, Math.min(128, sections));
        if (this.clientWorldSections != clamped) {
            this.clientWorldSections = clamped;
        }
    }

    private double clientSectionScale() {
        return Math.min(1.0D, 24.0D / Math.max(24, this.clientWorldSections));
    }

    public boolean isRunning() { return running; }

    public float sendRate(boolean localMemory) {
        float r = this.sendRate;
        return localMemory ? r : Math.min(r, MAX_REMOTE_SEND_RATE);
    }

    public Level level() { return effective; }

    public long packetBudgetNs() {
        if (isInitialWorldLoadWindow()) {
            return 450_000L;
        }
        return switch (effective) {
            case CALM -> (isInFrameHitch() || isServerTickPressured()) ? 600_000L : 1_350_000L;
            case MILD -> 600_000L;
            case SEVERE -> 450_000L;
        };
    }

    public int minChunkPacketsPerFrame() {
        return 1;
    }

    public int maxChunkPacketsPerFrame() {
        if (isInitialWorldLoadWindow()) {
            return 2;
        }
        int base = switch (effective) {
            case CALM -> isInFrameHitch() ? 3 : 8;
            case MILD -> 3;
            case SEVERE -> 2;
        };
        int floor = (effective == Level.CALM && !isInFrameHitch()) ? 2 : 1;
        int scaled = Math.max(floor, (int) Math.round(base * clientSectionScale()));
        if (isServerTickPressured()) {
            return Math.min(scaled, 3);
        }
        return scaled;
    }

    public int targetWorkers() { return targetWorkers; }

    /** Signals the start of a new client world load to smooth initial spawn mesh/light/packet pressure. */
    public void notifyWorldLoadStart() {
        this.initialWorldLoadFrames = 180;
        this.initialWorldLoadUntilNs = System.nanoTime() + 3_500_000_000L;
    }

    public long renderedFrameCount() {
        return this.renderedFrameCount;
    }

    public boolean hasActiveClientFrames() {
        long last = this.lastFrameNs;
        return last != 0L && (System.nanoTime() - last) < FRAME_STALE_NS && this.renderedFrameCount > 0L;
    }

    public boolean isInitialWorldLoadWindow() {
        if (!hasActiveClientFrames()) {
            return false;
        }
        if (this.initialWorldLoadFrames > 0) {
            return true;
        }
        long until = this.initialWorldLoadUntilNs;
        return until != 0L && System.nanoTime() < until;
    }

    public boolean isInFrameHitch() {
        if (!hasActiveClientFrames()) {
            return false;
        }
        if (this.hitchCooldownFrames > 0) {
            return true;
        }
        long until = this.hitchCooldownUntilNs;
        return until != 0L && System.nanoTime() < until;
    }

    public boolean isFramePressured() {
        return hasActiveClientFrames()
                && (this.effective != Level.CALM
                || isInFrameHitch()
                || this.lastFrameDtMs > Math.max(25.0D, this.baselineMs * 1.75D)
                || this.frameEwmaMs > Math.max(24.0D, this.baselineMs * 1.35D));
    }

    /**
     * Bounds concurrently dispatched tasks across {@link TaskQueue} and
     * {@link AreaLock}. In headless/dedicated-server mode, keeps full saturation
     * across 1-16+ workers; in live singleplayer client mode, backs off concurrency smoothly on
     * frame hitches and initial world load to protect 1% and 0.1% low FPS.
     */
    public int inFlightCap(int configuredWorkers) {
        if (this.testCapOverride) {
            return Math.max(3, this.targetWorkers + 2);
        }
        int w = Math.max(1, configuredWorkers);
        if (hasActiveClientFrames()) {
            if (this.effective == Level.SEVERE || this.lastGcFraction > 0.10D || isAllocationSevere()) {
                return Math.max(2, (w * 2) / 3);
            }
            if (this.effective == Level.MILD
                    || isInitialWorldLoadWindow()
                    || isInFrameHitch()
                    || isServerTickPressured()
                    || isAllocationPressured()) {
                return Math.max(2, w + 1);
            }
            // Keep configured workers 100% saturated with zero handoff bubbles (eliminates ForkJoinPool.deactivate)
            return w + Math.max(2, w / 2);
        }
        if (TickBudget.hasOnlinePlayers()) {
            if (this.effective == Level.SEVERE || this.lastGcFraction > 0.10D || isAllocationSevere()) {
                return Math.max(2, (w * 3) / 4);
            }
            if (this.effective == Level.MILD || isServerTickPressured() || isAllocationPressured()) {
                return Math.max(2, w + Math.max(1, w / 4));
            }
        }
        if (w == 1) {
            return 2;
        }
        if (w <= 4) {
            return w + 4;
        }
        if (w <= 8) {
            return w + 8;
        }
        if (w <= 16) {
            return w * 2;
        }
        return w * 3;
    }

    public double frameBaselineMs() { return baselineMs; }
    public double frameMs() { return frameEwmaMs; }
    public double spikeRate() { return spikeRate; }
    public double gcFraction() { return lastGcFraction; }
    public double allocRateMbPerSec() { return allocRateMbPerSec; }
    public double allocEwmaMbPerSec() { return allocEwmaMbPerSec; }

    public boolean isAllocationPressured() {
        return (hasActiveClientFrames() || TickBudget.hasOnlinePlayers())
                && this.allocEwmaMbPerSec > ALLOC_MILD_MB_S;
    }

    public boolean isAllocationSevere() {
        return (hasActiveClientFrames() || TickBudget.hasOnlinePlayers())
                && this.allocEwmaMbPerSec > ALLOC_SEVERE_MB_S;
    }

    /** Records an allocation-rate observation (MB/s); ignored when headless so Chunky pregen is never affected. */
    public void recordAllocationRateMbPerSec(double mbPerSec) {
        if (!hasActiveClientFrames() && !TickBudget.hasOnlinePlayers()) {
            return;
        }
        double clamped = Math.max(0.0D, Math.min(mbPerSec, 16_384.0D));
        this.allocRateMbPerSec = clamped;
        double prev = this.allocEwmaMbPerSec;
        double alpha = clamped > prev ? 0.35D : 0.15D;
        this.allocEwmaMbPerSec = prev + alpha * (clamped - prev);
    }

    public synchronized void start(int maxWorkers, Consumer<String> logSink) {
        if (running) {
            return;
        }
        init(maxWorkers, logSink);
        // Never throttle worldgen worker count below configured workers (v14 behavior)
        this.minWorkers = this.maxWorkers;
        this.minSendRateFloor = LIVE_MIN_SEND_RATE;
        this.testCapOverride = false;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Chunking-Governor");
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return t;
        });
        this.running = true;
        this.executor.scheduleWithFixedDelay(this::safeStep, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void init(int maxWorkers, Consumer<String> logSink) {
        synchronized (this.controlLock) {
            this.maxWorkers = Math.max(1, maxWorkers);
            this.minWorkers = logSink != null ? this.maxWorkers : Math.max(1, Math.min(this.maxWorkers, Math.max(4, this.maxWorkers / 4)));
            this.minSendRateFloor = logSink != null ? LIVE_MIN_SEND_RATE : MIN_SEND_RATE;
            this.targetWorkers = this.maxWorkers;
            this.testCapOverride = false;
            this.log = logSink;
            resetSessionLocked();
            this.gcBeans.clear();
            try {
                for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    String n = bean.getName();
                    if (!n.contains("Concurrent") && !n.contains("Cycles")) {
                        gcBeans.add(bean);
                    }
                }
            } catch (Throwable ignored) {
                gcBeans.clear();
            }
            this.lastGcPauseMs = totalGcPauseMs();
            this.lastGcWallNs = System.nanoTime();
        }
    }

    public void resetSession() {
        synchronized (this.controlLock) {
            resetSessionLocked();
        }
    }

    private void resetSessionLocked() {
        this.calmIntervals = 0;
        this.mildIntervals = 0;
        this.frameEwmaMs = 16.7D;
        this.baselineMs = 16.7D;
        this.spikeRate = 0.0D;
        this.windowMinMs = Double.POSITIVE_INFINITY;
        this.prevWindowMinMs = Double.POSITIVE_INFINITY;
        this.warmupFrames = 0;
        this.hitchCooldownFrames = 0;
        this.hitchCooldownUntilNs = 0L;
        this.initialWorldLoadFrames = 0;
        this.initialWorldLoadUntilNs = 0L;
        this.serverTickMs = 0.0D;
        this.serverTickEwmaMs = 0.0D;
        this.serverTickPeakMs = 0.0D;
        this.serverHitchTicks = 0;
        this.clientWorldSections = 24;
        this.allocRateMbPerSec = 0.0D;
        this.allocEwmaMbPerSec = 0.0D;
        this.lastAllocThreadIds = null;
        this.lastAllocTotalBytes = -1L;
        this.lastAllocWallNs = 0L;
        this.effective = Level.CALM;
        this.sendRate = MAX_LOCAL_SEND_RATE;
        this.targetWorkers = Math.max(1, this.maxWorkers);
    }

    void forceForTest(boolean runningFlag, int target) {
        this.running = runningFlag;
        this.testCapOverride = runningFlag;
        this.targetWorkers = target;
    }

    void testFrame(double ms) {
        this.lastFrameNs = System.nanoTime();
        this.renderedFrameCount++;
        recordFrameMs(ms);
    }

    public void stop() {
        ScheduledExecutorService exec;
        synchronized (this.controlLock) {
            this.running = false;
            this.testCapOverride = false;
            exec = this.executor;
            this.executor = null;
            resetSessionLocked();
            this.lastFrameNs = 0L;
            this.renderedFrameCount = 0L;
        }
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    private void safeStep() {
        try {
            step();
        } catch (Throwable ignored) {
        }
    }

    void step() {
        Level level = measure();
        applyControl(level);
    }

    private Level measure() {
        Level level = Level.CALM;

        double curTick;
        double peakTick;
        synchronized (this.controlLock) {
            curTick = this.serverTickMs;
            peakTick = Math.max(curTick, this.serverTickPeakMs);
            this.serverTickPeakMs = curTick;

            // Smooth decay when ticks are calm (also supports synthetic step() loops in unit tests)
            if (curTick <= TICK_CALM_EXIT_MS) {
                this.serverTickEwmaMs += 0.25D * (curTick - this.serverTickEwmaMs);
                if (this.serverHitchTicks > 0) {
                    this.serverHitchTicks = Math.max(0, this.serverHitchTicks - 2);
                }
            }
        }
        double ewmaTick = this.serverTickEwmaMs;
        Level curEffective = this.effective;

        if (peakTick > TICK_SEVERE_MS || ewmaTick > TICK_SEVERE_MS - 2.0D) {
            level = Level.SEVERE;
        } else if (peakTick > TICK_MILD_MS
                || ewmaTick > TICK_MILD_MS - 1.0D
                || this.serverHitchTicks > 0
                || (curEffective == Level.SEVERE && (peakTick > TICK_SEVERE_EXIT_MS || ewmaTick > TICK_CALM_EXIT_MS))
                || (curEffective == Level.MILD && (peakTick > TICK_CALM_EXIT_MS || ewmaTick > TICK_CALM_EXIT_MS))) {
            level = max(level, Level.MILD);
        }

        long last = this.lastFrameNs;
        boolean framesValid = last != 0L && (System.nanoTime() - last) < FRAME_STALE_NS;
        if (framesValid) {
            double ewma = this.frameEwmaMs;
            double base = this.baselineMs;
            double spikes = this.spikeRate;
            if (ewma > base * 2.0D + 4.0D || spikes > SPIKE_SEVERE) {
                level = Level.SEVERE;
            } else if (ewma > base * 1.4D + 2.0D || spikes > SPIKE_MILD) {
                level = max(level, Level.MILD);
            }
        }

        long nowNs = System.nanoTime();
        long pauseMs = totalGcPauseMs();
        double wallMs = (nowNs - lastGcWallNs) / 1_000_000.0D;
        if (wallMs > 0.0D) {
            double frac = Math.max(0L, pauseMs - lastGcPauseMs) / wallMs;
            this.lastGcFraction = frac;
            if (frac > GC_SEVERE) {
                level = Level.SEVERE;
            } else if (frac > GC_MILD) {
                level = max(level, Level.MILD);
            }
        }
        lastGcPauseMs = pauseMs;
        lastGcWallNs = nowNs;

        boolean liveGameplay = framesValid || TickBudget.hasOnlinePlayers();
        if (!liveGameplay) {
            // Stay 100% inactive during headless pregeneration to avoid ThreadMXBean overhead and throttling.
            this.allocRateMbPerSec = 0.0D;
            this.allocEwmaMbPerSec = 0.0D;
            this.lastAllocThreadIds = null;
            this.lastAllocTotalBytes = -1L;
            this.lastAllocWallNs = 0L;
        } else {
            sampleWorkerAllocationRate(nowNs);
            double allocEwma = this.allocEwmaMbPerSec;
            if (allocEwma > ALLOC_SEVERE_MB_S) {
                level = Level.SEVERE;
            } else if (allocEwma > ALLOC_MILD_MB_S) {
                level = max(level, Level.MILD);
            }
        }
        return level;
    }

    private void sampleWorkerAllocationRate(long nowNs) {
        if (THREAD_ALLOC_BEAN == null) {
            return;
        }
        long[] ids = ChunkEngine.get().workerPool().activeWorkerThreadIds();
        if (ids.length == 0) {
            this.lastAllocThreadIds = null;
            this.lastAllocTotalBytes = -1L;
            this.lastAllocWallNs = 0L;
            return;
        }
        if (this.lastAllocWallNs != 0L && (nowNs - this.lastAllocWallNs) < 20_000_000L) {
            return;
        }
        long[] bytes = THREAD_ALLOC_BEAN.getThreadAllocatedBytes(ids);
        long total = 0L;
        for (int i = 0; i < bytes.length; i++) {
            long b = bytes[i];
            if (b > 0L) {
                total += b;
            }
        }
        // Reset baseline when the worker thread set changes so new/exited threads never skew the delta.
        if (ids == this.lastAllocThreadIds && this.lastAllocTotalBytes >= 0L && this.lastAllocWallNs != 0L) {
            long delta = total - this.lastAllocTotalBytes;
            double wallSec = (nowNs - this.lastAllocWallNs) / 1_000_000_000.0D;
            if (delta >= 0L && wallSec >= 0.02D) {
                double mbPerSec = (delta / (1024.0D * 1024.0D)) / wallSec;
                recordAllocationRateMbPerSec(mbPerSec);
            }
        }
        this.lastAllocThreadIds = ids;
        this.lastAllocTotalBytes = total;
        this.lastAllocWallNs = nowNs;
    }

    private void applyControl(Level level) {
        synchronized (this.controlLock) {
            float rate = this.sendRate;
            int workers = this.targetWorkers;
            boolean serverPressured = isServerTickPressured();
            float activeFloor = serverPressured ? MIN_SEND_RATE : this.minSendRateFloor;

            switch (level) {
                case SEVERE -> {
                    calmIntervals = 0;
                    mildIntervals = 0;
                    rate = Math.max(activeFloor, rate * 0.75F);
                    workers = Math.max(minWorkers, workers - 2);
                    effective = Level.SEVERE;
                }
                case MILD -> {
                    calmIntervals = 0;
                    mildIntervals++;
                    float mildFloor = serverPressured ? Math.max(MIN_SEND_RATE, 12.0F) : activeFloor;
                    rate = Math.max(mildFloor, rate * 0.90F);
                    if (mildIntervals % 3 == 0) {
                        workers = Math.max(minWorkers, workers - 1);
                    }
                    if (effective == Level.CALM) {
                        effective = Level.MILD;
                    }
                }
                case CALM -> {
                    mildIntervals = 0;
                    calmIntervals++;
                    if (calmIntervals >= 4) {
                        rate = Math.min(MAX_LOCAL_SEND_RATE, rate + 2.0F);
                        if (calmIntervals % 3 == 0) {
                            workers = Math.min(maxWorkers, workers + 1);
                        }
                    }
                    if (calmIntervals % 4 == 0) {
                        if (effective == Level.SEVERE) {
                            effective = Level.MILD;
                        } else if (effective == Level.MILD) {
                            effective = Level.CALM;
                        }
                    }
                }
            }

            this.sendRate = rate;
            if (workers != this.targetWorkers) {
                this.targetWorkers = workers;
            }
            maybeLog(level);
        }
    }

    private long totalGcPauseMs() {
        long total = 0L;
        for (int i = 0; i < gcBeans.size(); i++) {
            long t = gcBeans.get(i).getCollectionTime();
            if (t > 0L) {
                total += t;
            }
        }
        return total;
    }

    private void maybeLog(Level measured) {
        Consumer<String> sink = this.log;
        if (sink == null || !Boolean.getBoolean("chunking.governor_log")) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNs < 1_000_000_000L) {
            return;
        }
        lastLogNs = now;
        sink.accept(String.format(
                "[Governor] level=%s now=%s workers=%d/%d sendRate=%.0f frame=%.1fms(base %.1f) spikes=%.1f%% tick=%.1fms(ewma %.1f) gc=%.1f%% alloc=%.0fMB/s",
                measured, effective, targetWorkers, maxWorkers, sendRate, frameEwmaMs, baselineMs,
                spikeRate * 100.0D, serverTickMs, serverTickEwmaMs, lastGcFraction * 100.0D, allocEwmaMbPerSec));
    }

    private static Level max(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
