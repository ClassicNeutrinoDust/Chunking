package com.chunking.engine.scheduler;

import net.minecraft.server.MinecraftServer;

/**
 * Pure, unit-testable tick-time budgeting utilities used by {@code PlayerChunkSenderMixin}
 * and {@code ServerChunkCacheMixin}. Paces local chunk streaming up to 36 chunks/tick
 * (720 chunks/sec continuous stream) while preventing client render-thread packet bursts and
 * protecting 20.0 TPS when players are online under heavy chunk generation.
 */
public final class TickBudget {
    public static final float MAX_LOCAL_CHUNKS_PER_TICK = 64.0F;
    public static final float MAX_REMOTE_CHUNKS_PER_TICK = 48.0F;
    public static final float LIVE_LOCAL_BATCH_CAP = 36.0F;
    public static final float LIVE_REMOTE_BATCH_CAP = 32.0F;

    private static volatile double lastInstantTickMs = 5.0D;
    private static final int MSPT_WINDOW = 20;
    private static final double[] TICK_MS_RING = new double[MSPT_WINDOW];
    private static int tickRingIdx = 0;
    private static int tickRingCount = 0;
    private static volatile double smoothedTickMs = 5.0D;
    private static volatile double recentPeakTickMs = 5.0D;
    private static volatile int lastAccumTickCount = -1;
    private static volatile double currentTickAccumMs = 0.0D;
    private static volatile int lastGovernorTickCount = -1;
    private static volatile int lastFullTickCount = -1;
    private static volatile int lastPlayerTickCount = -1;
    private static volatile int currentTickPlayers = 0;
    private static volatile int onlinePlayerCount = 0;
    private static final ThreadLocal<int[]> MANAGED_BLOCK_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private TickBudget() {}

    public static void enterManagedBlock() {
        MANAGED_BLOCK_DEPTH.get()[0]++;
    }

    public static void exitManagedBlock() {
        int[] depth = MANAGED_BLOCK_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public static boolean isInManagedBlock() {
        return MANAGED_BLOCK_DEPTH.get()[0] > 0;
    }

    public static void resetSession() {
        lastInstantTickMs = 5.0D;
        tickRingIdx = 0;
        tickRingCount = 0;
        smoothedTickMs = 5.0D;
        recentPeakTickMs = 5.0D;
        lastAccumTickCount = -1;
        currentTickAccumMs = 0.0D;
        lastGovernorTickCount = -1;
        lastFullTickCount = -1;
        lastPlayerTickCount = -1;
        currentTickPlayers = 0;
        onlinePlayerCount = 0;
    }

    public static void recordLastTickMs(double tickMs) {
        if (tickMs >= 0.0D) {
            lastInstantTickMs = tickMs;
        }
    }

    public static void recordFullTickMs(int tickCount, double fullMs) {
        if (fullMs < 0.0D) {
            return;
        }
        lastFullTickCount = tickCount;
        lastInstantTickMs = fullMs;
        TICK_MS_RING[tickRingIdx] = fullMs;
        tickRingIdx = (tickRingIdx + 1) % MSPT_WINDOW;
        if (tickRingCount < MSPT_WINDOW) {
            tickRingCount++;
        }
        double sum = 0.0D;
        double max = 0.0D;
        for (int i = 0; i < tickRingCount; i++) {
            double v = TICK_MS_RING[i];
            sum += v;
            if (v > max) {
                max = v;
            }
        }
        smoothedTickMs = sum / tickRingCount;
        recentPeakTickMs = max;
    }

    public static void recordDimensionTickMs(int tickCount, double tickMs) {
        if (tickMs < 0.0D || tickCount == lastFullTickCount) {
            return;
        }
        if (tickCount != lastAccumTickCount) {
            lastAccumTickCount = tickCount;
            currentTickAccumMs = tickMs;
        } else {
            currentTickAccumMs += tickMs;
        }
        // Keep the previous full-tick MSPT until the current tick's work exceeds it or finishes.
        if (lastFullTickCount < 0 || currentTickAccumMs > lastInstantTickMs) {
            lastInstantTickMs = currentTickAccumMs;
        }
    }

    public static void recordOnlinePlayers(int count) {
        onlinePlayerCount = Math.max(0, count);
    }

    /** Accumulates player count across dimensions within the same server tick without flickering to 0 mid-tick. */
    public static void recordDimensionPlayers(int tickCount, int dimensionPlayers) {
        int clamped = Math.max(0, dimensionPlayers);
        if (tickCount != lastPlayerTickCount) {
            int prevTotal = currentTickPlayers;
            lastPlayerTickCount = tickCount;
            currentTickPlayers = clamped;
            if (clamped > 0 || prevTotal == 0) {
                onlinePlayerCount = clamped;
            }
        } else {
            currentTickPlayers += clamped;
            onlinePlayerCount = currentTickPlayers;
        }
    }

    public static boolean hasOnlinePlayers() {
        return onlinePlayerCount > 0;
    }

    public static int onlinePlayerCount() {
        return onlinePlayerCount;
    }

    public static boolean claimGovernorTick(int tickCount) {
        if (tickCount == lastGovernorTickCount) {
            return false;
        }
        lastGovernorTickCount = tickCount;
        return true;
    }

    public static double lastRecordedTickMs() {
        return lastInstantTickMs;
    }

    public static double smoothedTickMs() {
        return smoothedTickMs;
    }

    public static double recentPeakTickMs() {
        return recentPeakTickMs;
    }

    public static double measureEffectiveTickMs(MinecraftServer server) {
        if (server == null) {
            return 0.0D;
        }
        double avgMs = server.getAverageTickTimeNanos() / 1_000_000.0D;
        return Math.max(avgMs, lastInstantTickMs);
    }

    /**
     * Computes the per-tick main-thread {@code pollTask()} drain time budget (in nanoseconds)
     * when players are online or client frames are active. Returns {@link Long#MAX_VALUE} when
     * no players are online or when MSPT is healthy (&lt; 25 ms and unpressured) so dedicated-server
     * Chunky pregeneration is never throttled.
     */
    public static long computePollDrainBudgetNs(
            boolean playersOrClientActive,
            double effectiveTickMs,
            boolean serverPressured,
            FrameGuard.Level level
    ) {
        if (!playersOrClientActive) {
            return Long.MAX_VALUE;
        }
        // Only throttle server main-thread pollTask() drain when ACTUAL server MSPT is high;
        // never let client render-frame jitter starve integrated-server chunk promotion!
        if (effectiveTickMs >= 38.0D) {
            return 4_000_000L; // 4.0 ms drain budget under genuine >38 ms server tick spike
        }
        if (effectiveTickMs >= 30.0D || serverPressured) {
            return 8_000_000L; // 8.0 ms drain budget under mild >30 ms server tick pressure
        }
        return Long.MAX_VALUE;
    }

    public static float computeChunkSendRate(boolean localMemory, double effectiveTickMs) {
        FrameGuard gov = FrameGuard.get();
        if (gov.isRunning()) {
            float liveCap = localMemory ? LIVE_LOCAL_BATCH_CAP : LIVE_REMOTE_BATCH_CAP;
            float rate = Math.min(liveCap, gov.sendRate(localMemory));
            // Immediate single-tick spike protection without waiting for the 100 ms governor timer
            if (effectiveTickMs > 35.0D) {
                return Math.min(rate, localMemory ? 8.0F : 6.0F);
            }
            if (effectiveTickMs > 28.0D) {
                return Math.min(rate, localMemory ? 16.0F : 12.0F);
            }
            return rate;
        }
        return computeChunkSendRateStateless(localMemory, effectiveTickMs);
    }

    public static float computeChunkSendRateStateless(boolean localMemory, double effectiveTickMs) {
        float cap = localMemory ? MAX_LOCAL_CHUNKS_PER_TICK : MAX_REMOTE_CHUNKS_PER_TICK;
        if (effectiveTickMs <= 22.0D) {
            return cap;
        }
        if (effectiveTickMs <= 30.0D) {
            return Math.min(cap, 32.0F);
        }
        if (effectiveTickMs <= 40.0D) {
            return Math.min(cap, 16.0F);
        }
        return 4.0F;
    }

    public static boolean shouldAllowPredictivePreStage(double effectiveTickMs) {
        return effectiveTickMs <= 30.0D;
    }
}
