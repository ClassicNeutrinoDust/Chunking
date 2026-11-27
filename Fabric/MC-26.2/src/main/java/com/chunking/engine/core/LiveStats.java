package com.chunking.engine.core;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.TaskQueue;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.scheduler.MoveTracker;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free live telemetry for FPS, rolling CPS, MSPT, and worker utilization.
 */
public final class LiveStats {
    private static final LiveStats INSTANCE = new LiveStats();

    private static final int FRAME_RING_SIZE = 1024;
    private static final int FRAME_RING_MASK = FRAME_RING_SIZE - 1;
    private static final int CPS_BUCKETS = 4; // 4 x 250ms = 1.0s rolling window
    private static final long SAMPLE_INTERVAL_NS = 250_000_000L;

    public static final class Snapshot {
        public double fpsNow;
        public double fpsAvg;
        public double fps1PctLow;
        public double fps01PctLow;
        public double frameP99Ms;
        public double msptMs;
        public double msptPeakMs;
        public double speedBlocksPerSec;
        public boolean isMoving;

        public double clientLoadCps;
        public double serverSendCps;
        public double featuresCps;
        public double noiseCps;
        public double surfaceCps;
        public double biomesCps;
        public double structureCps;

        public double movingAvgLoadCps;
        public double movingPeakLoadCps;
        public double movingAvgNoiseCps;
        public double movingPeakNoiseCps;

        public int workers;
        public int activeUpstream;
        public int activeFeatures;
        public int pendingFeatures;
        public long poolQueueDepth;
        public FrameGuard.Level govLevel;
    }

    private final LongAdder clientLoads = new LongAdder();
    private final LongAdder serverSends = new LongAdder();
    private final LongAdder featureDone = new LongAdder();
    private final LongAdder noiseDone = new LongAdder();
    private final LongAdder surfaceDone = new LongAdder();
    private final LongAdder biomesDone = new LongAdder();
    private final LongAdder structureDone = new LongAdder();

    // Frame-time ring buffer (written/read on Render Thread only -> zero locking, zero GC)
    private final double[] frameRingMs = new double[FRAME_RING_SIZE];
    private final double[] sortScratchMs = new double[FRAME_RING_SIZE];
    private int frameWriteIdx = 0;
    private int frameCount = 0;
    private long lastFrameNs = 0L;
    private double instantFps = 0.0D;
    private volatile int resetEpoch = 0;
    private int observedResetEpoch = 0;

    // 4 x 250ms sliding window for CPS
    private final long[] dLoads = new long[CPS_BUCKETS];
    private final long[] dSends = new long[CPS_BUCKETS];
    private final long[] dFeatures = new long[CPS_BUCKETS];
    private final long[] dNoise = new long[CPS_BUCKETS];
    private final long[] dSurface = new long[CPS_BUCKETS];
    private final long[] dBiomes = new long[CPS_BUCKETS];
    private final long[] dStructure = new long[CPS_BUCKETS];
    private final long[] dElapsedNs = new long[CPS_BUCKETS];
    private int bucketIdx = 0;
    private long lastSampleNs = 0L;

    private long prevLoads, prevSends, prevFeatures, prevNoise, prevSurface, prevBiomes, prevStructure;
    private long cachedPoolQueueDepth = 0L;

    private double movingLoadSum = 0.0D;
    private double movingNoiseSum = 0.0D;
    private double movingPeakLoad = 0.0D;
    private double movingPeakNoise = 0.0D;
    private int movingSamples = 0;

    private long lastPercentileNs = 0L;
    private double cachedFpsAvg = 0.0D;
    private double cachedFrameP99Ms = 0.0D;
    private double cachedFps1PctLow = 0.0D;
    private double cachedFps01PctLow = 0.0D;

    private final Snapshot cachedSnapshot = new Snapshot();

    private LiveStats() {}

    public static LiveStats get() {
        return INSTANCE;
    }

    public void recordClientChunkLoaded() {
        clientLoads.increment();
    }

    public void recordChunksSent(int count) {
        if (count > 0) {
            serverSends.add(count);
        }
    }

    public long totalServerChunksSent() {
        return serverSends.sum();
    }

    public void recordStageCompleted(StageTimer.Stage stage) {
        switch (stage) {
            case FEATURES -> featureDone.increment();
            case NOISE -> noiseDone.increment();
            case SURFACE -> surfaceDone.increment();
            case BIOMES -> biomesDone.increment();
            case STRUCTURE_STARTS -> structureDone.increment();
            default -> {}
        }
    }

    /** Render-thread only, O(1), zero allocation. */
    public void onFrame() {
        onFrame(System.nanoTime());
    }

    /** Render-thread only, O(1), zero allocation, reusing a caller-supplied timestamp. */
    public void onFrame(long now) {
        checkRenderReset();
        long prev = this.lastFrameNs;
        this.lastFrameNs = now;
        if (prev == 0L) {
            return;
        }
        double dtMs = (now - prev) / 1_000_000.0D;
        if (dtMs <= 0.05D) {
            return;
        }
        if (dtMs > 5000.0D) {
            dtMs = 5000.0D;
        }
        this.frameRingMs[this.frameWriteIdx] = dtMs;
        this.frameWriteIdx = (this.frameWriteIdx + 1) & FRAME_RING_MASK;
        if (this.frameCount < FRAME_RING_SIZE) {
            this.frameCount++;
        }
        double inst = 1000.0D / dtMs;
        this.instantFps = this.instantFps == 0.0D ? inst : (this.instantFps * 0.85D + inst * 0.15D);
    }

    public void resetSession() {
        this.clientLoads.reset();
        this.serverSends.reset();
        this.featureDone.reset();
        this.noiseDone.reset();
        this.surfaceDone.reset();
        this.biomesDone.reset();
        this.structureDone.reset();
        this.resetEpoch++;
    }

    private void checkRenderReset() {
        int epoch = this.resetEpoch;
        if (epoch == this.observedResetEpoch) {
            return;
        }
        this.observedResetEpoch = epoch;
        this.frameWriteIdx = 0;
        this.frameCount = 0;
        this.lastFrameNs = 0L;
        this.instantFps = 0.0D;
        Arrays.fill(this.dLoads, 0L);
        Arrays.fill(this.dSends, 0L);
        Arrays.fill(this.dFeatures, 0L);
        Arrays.fill(this.dNoise, 0L);
        Arrays.fill(this.dSurface, 0L);
        Arrays.fill(this.dBiomes, 0L);
        Arrays.fill(this.dStructure, 0L);
        Arrays.fill(this.dElapsedNs, 0L);
        this.bucketIdx = 0;
        this.lastSampleNs = System.nanoTime();
        this.prevLoads = this.clientLoads.sum();
        this.prevSends = this.serverSends.sum();
        this.prevFeatures = this.featureDone.sum();
        this.prevNoise = this.noiseDone.sum();
        this.prevSurface = this.surfaceDone.sum();
        this.prevBiomes = this.biomesDone.sum();
        this.prevStructure = this.structureDone.sum();
        this.cachedPoolQueueDepth = 0L;
        this.movingLoadSum = 0.0D;
        this.movingNoiseSum = 0.0D;
        this.movingPeakLoad = 0.0D;
        this.movingPeakNoise = 0.0D;
        this.movingSamples = 0;
        this.lastPercentileNs = 0L;
        this.cachedFpsAvg = 0.0D;
        this.cachedFrameP99Ms = 0.0D;
        this.cachedFps1PctLow = 0.0D;
        this.cachedFps01PctLow = 0.0D;
    }

    /** Render-thread snapshot, updates CPS sliding window every 250 ms with zero heap allocation. */
    public Snapshot sampleSnapshot() {
        checkRenderReset();
        long now = System.nanoTime();
        ChunkEngine eng = ChunkEngine.get();
        if (this.lastSampleNs == 0L) {
            this.lastSampleNs = now;
            this.prevLoads = this.clientLoads.sum();
            this.prevSends = this.serverSends.sum();
            this.prevFeatures = this.featureDone.sum();
            this.prevNoise = this.noiseDone.sum();
            this.prevSurface = this.surfaceDone.sum();
            this.prevBiomes = this.biomesDone.sum();
            this.prevStructure = this.structureDone.sum();
            this.cachedPoolQueueDepth = eng.workerPool().queueDepth();
        }

        double speedBps = 0.0D;
        MoveTracker.PlayerMotion[] motions = eng.velocityTracker().getAnyActiveMotions();
        if (motions != null && motions.length > 0 && motions[0] != null) {
            speedBps = motions[0].speedXZ * 20.0D; // blocks/tick -> blocks/sec
        }
        boolean moving = speedBps >= 4.0D;

        if (now - this.lastSampleNs >= SAMPLE_INTERVAL_NS) {
            long elapsedNs = this.lastSampleNs == 0L ? SAMPLE_INTERVAL_NS : Math.min(2_000_000_000L, now - this.lastSampleNs);
            this.lastSampleNs = now;
            this.cachedPoolQueueDepth = eng.workerPool().queueDepth();
            long cLoads = this.clientLoads.sum();
            long cSends = this.serverSends.sum();
            long cFeat = this.featureDone.sum();
            long cNoise = this.noiseDone.sum();
            long cSurf = this.surfaceDone.sum();
            long cBio = this.biomesDone.sum();
            long cStr = this.structureDone.sum();

            int b = this.bucketIdx;
            this.dLoads[b] = Math.max(0L, cLoads - this.prevLoads);
            this.dSends[b] = Math.max(0L, cSends - this.prevSends);
            this.dFeatures[b] = Math.max(0L, cFeat - this.prevFeatures);
            this.dNoise[b] = Math.max(0L, cNoise - this.prevNoise);
            this.dSurface[b] = Math.max(0L, cSurf - this.prevSurface);
            this.dBiomes[b] = Math.max(0L, cBio - this.prevBiomes);
            this.dStructure[b] = Math.max(0L, cStr - this.prevStructure);
            this.dElapsedNs[b] = elapsedNs;
            this.bucketIdx = (b + 1) % CPS_BUCKETS;

            this.prevLoads = cLoads;
            this.prevSends = cSends;
            this.prevFeatures = cFeat;
            this.prevNoise = cNoise;
            this.prevSurface = cSurf;
            this.prevBiomes = cBio;
            this.prevStructure = cStr;

            double windowSec = Math.max(0.25D, sum(this.dElapsedNs) / 1_000_000_000.0D);
            double curLoadCps = sum(this.dLoads) / windowSec;
            double curNoiseCps = sum(this.dNoise) / windowSec;
            if (moving) {
                this.movingSamples++;
                this.movingLoadSum += curLoadCps;
                this.movingNoiseSum += curNoiseCps;
                if (curLoadCps > this.movingPeakLoad) {
                    this.movingPeakLoad = curLoadCps;
                }
                if (curNoiseCps > this.movingPeakNoise) {
                    this.movingPeakNoise = curNoiseCps;
                }
            }
        }

        double windowSec = Math.max(0.25D, sum(this.dElapsedNs) / 1_000_000_000.0D);
        Snapshot s = this.cachedSnapshot;
        s.fpsNow = this.instantFps;
        s.speedBlocksPerSec = speedBps;
        s.isMoving = moving;
        s.msptMs = TickBudget.smoothedTickMs();
        s.msptPeakMs = TickBudget.recentPeakTickMs();
        s.clientLoadCps = sum(this.dLoads) / windowSec;
        s.serverSendCps = sum(this.dSends) / windowSec;
        s.featuresCps = sum(this.dFeatures) / windowSec;
        s.noiseCps = sum(this.dNoise) / windowSec;
        s.surfaceCps = sum(this.dSurface) / windowSec;
        s.biomesCps = sum(this.dBiomes) / windowSec;
        s.structureCps = sum(this.dStructure) / windowSec;

        s.movingAvgLoadCps = this.movingSamples > 0 ? (this.movingLoadSum / this.movingSamples) : 0.0D;
        s.movingPeakLoadCps = this.movingPeakLoad;
        s.movingAvgNoiseCps = this.movingSamples > 0 ? (this.movingNoiseSum / this.movingSamples) : 0.0D;
        s.movingPeakNoiseCps = this.movingPeakNoise;

        s.workers = ModConfig.WORLDGEN_WORKERS;
        s.activeUpstream = TaskQueue.inFlightCount();
        s.activeFeatures = eng.featureLock().activeDispatchedCount();
        s.pendingFeatures = eng.featureLock().pendingCount();
        s.poolQueueDepth = this.cachedPoolQueueDepth;
        s.govLevel = FrameGuard.get().level();

        int n = this.frameCount;
        if (n > 0) {
            if (now - this.lastPercentileNs >= 100_000_000L || this.cachedFpsAvg == 0.0D) {
                this.lastPercentileNs = now;
                System.arraycopy(this.frameRingMs, 0, this.sortScratchMs, 0, n);
                Arrays.sort(this.sortScratchMs, 0, n);
                double totalMs = 0.0D;
                for (int i = 0; i < n; i++) {
                    totalMs += this.sortScratchMs[i];
                }
                int idx1 = Math.min(n - 1, Math.max(0, (int) Math.floor(n * 0.99D)));
                int idx01 = Math.min(n - 1, Math.max(0, (int) Math.floor(n * 0.999D)));
                double p99Ms = this.sortScratchMs[idx1];
                double p999Ms = this.sortScratchMs[idx01];
                this.cachedFpsAvg = (n * 1000.0D) / Math.max(0.001D, totalMs);
                this.cachedFrameP99Ms = p99Ms;
                this.cachedFps1PctLow = 1000.0D / Math.max(0.1D, p99Ms);
                this.cachedFps01PctLow = 1000.0D / Math.max(0.1D, p999Ms);
            }
            s.fpsAvg = this.cachedFpsAvg;
            s.frameP99Ms = this.cachedFrameP99Ms;
            s.fps1PctLow = this.cachedFps1PctLow;
            s.fps01PctLow = this.cachedFps01PctLow;
        } else {
            s.fpsAvg = 0.0D;
            s.frameP99Ms = 0.0D;
            s.fps1PctLow = 0.0D;
            s.fps01PctLow = 0.0D;
        }
        return s;
    }

    private static double sum(long[] arr) {
        long t = 0L;
        for (int i = 0; i < arr.length; i++) {
            t += arr[i];
        }
        return (double) t;
    }
}
