package com.chunking.engine.client;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.scheduler.FrameGuard;
import net.minecraft.client.Minecraft;

/**
 * Client-side runtime coordinator for framerate-independent 3D movement speed tracking,
 * unified render-thread per-frame streaming budgets with overshoot debt smoothing,
 * fast single-frame spike detection, GUI-transition grace windows, and widened render-distance sliders.
 */
public final class ClientRuntime {
    private static final ClientRuntime INSTANCE = new ClientRuntime();
    private static final double FAST_MOVEMENT_THRESHOLD_BPS = 12.0D;
    private static final double FAST_TURN_THRESHOLD_DPS = 140.0D;

    // Smooth initial budget active immediately on world reset before the first rendered frame
    private static final long INITIAL_WORLD_LOAD_BUDGET_NS = 2_500_000L;

    // Brief grace window when opening an in-game GUI (Inventory / Chest / Pause)
    private static final long GUI_TRANSITION_GRACE_NS = 140_000_000L;

    // Render-thread-only camera velocity state
    private double prevCamX = Double.NaN;
    private double prevCamY = Double.NaN;
    private double prevCamZ = Double.NaN;
    private float prevCamYaw = Float.NaN;
    private float prevCamPitch = Float.NaN;
    private long prevCamNs = 0L;
    private double smoothedSpeedBps = 0.0D;
    private double smoothedTurnDegPerSec = 0.0D;

    private static long streamingBudgetTotalNs = 0L;
    private static long streamingBudgetSpentNs = 0L;
    private static int consecutiveDeferredLightFrames = 0;

    // Fast single-frame spike detector (written by render thread, read cross-thread by integrated server)
    private static long lastRenderFrameNs = 0L;
    private static long localFrameEwmaNs = 16_666_667L;
    private static volatile int localSpikeCooldownFrames = 0;

    // Zero-allocation GUI-transition grace window (written by render thread, read cross-thread by integrated server)
    private static volatile long guiTransitionUntilNs = 0L;

    private ClientRuntime() {}

    public static ClientRuntime get() {
        return INSTANCE;
    }

    /**
     * Starts a brief 140 ms grace window when an in-game GUI screen opens so widget/recipe-book
     * initialization and first-frame rendering do not collide with heavy chunk streaming bursts.
     */
    public static void notifyGuiTransition() {
        long until = System.nanoTime() + GUI_TRANSITION_GRACE_NS;
        guiTransitionUntilNs = until == 0L ? 1L : until;
    }

    /** Thread-safe check for whether the client is within the brief GUI-opening grace window. */
    public static boolean isInGuiTransition() {
        long until = guiTransitionUntilNs;
        return until != 0L && (until - System.nanoTime()) > 0L;
    }

    /**
     * Records render-frame cadence on the render thread to detect single-frame hitches immediately
     * (including high-refresh 120–240 Hz spikes below {@link FrameGuard}'s 18 ms hitch floor).
     */
    public static void onRenderFrame(long nowNs, double governorBaselineMs) {
        long prevNs = lastRenderFrameNs;
        lastRenderFrameNs = nowNs;
        if (prevNs == 0L) {
            return;
        }
        long dtNs = nowNs - prevNs;
        // Ignore non-positive deltas or long pauses (window minimize / loading screen)
        if (dtNs <= 0L || dtNs > 500_000_000L) {
            return;
        }
        long ewma = localFrameEwmaNs;
        ewma += (dtNs - ewma) >> 3;
        localFrameEwmaNs = Math.max(2_000_000L, ewma);

        long govBaseNs = (long) (Math.max(4.0D, governorBaselineMs) * 1_000_000.0D);
        long baseNs = Math.max(4_000_000L, Math.min(ewma, govBaseNs));
        long spikeThresholdNs = Math.max(18_000_000L, (long) (baseNs * 1.85D));
        if (dtNs > spikeThresholdNs) {
            int cooldown = dtNs > (baseNs * 3L) ? 6 : 3;
            if (cooldown > localSpikeCooldownFrames) {
                localSpikeCooldownFrames = cooldown;
            }
        } else if (localSpikeCooldownFrames > 0) {
            localSpikeCooldownFrames--;
        }
    }

    /** Thread-safe check for whether the client render thread recently experienced a single-frame spike. */
    public static boolean isInLocalFrameSpike() {
        return localSpikeCooldownFrames > 0;
    }

    /**
     * Computes a baseline-aware unified per-frame streaming budget so 60 FPS clients retain full
     * high-CPS ingestion (2.5 ms/frame) while 120–240 FPS clients scale smoothly to avoid eating
     * 35%+ of a single frame (protecting 0.1% low FPS without sacrificing per-second CPS).
     */
    public static long computeFrameStreamingBudgetNs(FrameGuard gov) {
        FrameGuard.Level lvl = gov.level();
        boolean initialLoad = gov.isInitialWorldLoadWindow();
        boolean hitch = gov.isInFrameHitch();
        boolean localSpike = localSpikeCooldownFrames > 0;
        long baselineNs = (long) (Math.max(4.0D, gov.frameBaselineMs()) * 1_000_000.0D);

        ClientRuntime rt = INSTANCE;
        boolean turningFast = rt.isTurningFast();
        boolean movingFast = rt.isMovingFast();
        if (lvl == FrameGuard.Level.SEVERE || hitch) {
            long scaled = (long) (baselineNs * 0.18D);
            return Math.max(1_000_000L, Math.min(1_500_000L, scaled));
        }
        if (initialLoad || lvl == FrameGuard.Level.MILD || gov.isFramePressured() || localSpike) {
            if (turningFast) {
                long scaled = (long) (baselineNs * 0.20D);
                return Math.max(1_200_000L, Math.min(1_800_000L, scaled));
            }
            long scaled = (long) (baselineNs * 0.24D);
            return Math.max(1_500_000L, Math.min(2_400_000L, scaled));
        }
        if (turningFast) {
            long scaled = (long) (baselineNs * 0.22D);
            return Math.max(1_400_000L, Math.min(2_200_000L, scaled));
        }
        if (movingFast) {
            long scaled = (long) (baselineNs * 0.38D);
            return Math.max(3_200_000L, Math.min(4_500_000L, scaled));
        }
        long scaled = (long) (baselineNs * 0.30D);
        return Math.max(2_200_000L, Math.min(3_500_000L, scaled));
    }

    public static void beginFrameStreamingBudget(long totalBudgetNs) {
        long nextTotal = Math.max(0L, totalBudgetNs);
        long carryDebt = 0L;
        if (streamingBudgetTotalNs > 0L && streamingBudgetSpentNs > streamingBudgetTotalNs) {
            long overshoot = streamingBudgetSpentNs - streamingBudgetTotalNs;
            // Carry over half of any previous-frame overshoot, capped at 45% of the new frame budget
            // so back-to-back hitches are smoothed without ever starving streaming to zero.
            carryDebt = Math.min(overshoot >>> 1, (nextTotal * 9L) / 20L);
        }
        streamingBudgetTotalNs = nextTotal;
        streamingBudgetSpentNs = carryDebt;
    }

    public static long remainingStreamingBudgetNs(long subsystemCapNs) {
        long cap = Math.max(0L, subsystemCapNs);
        long total = streamingBudgetTotalNs;
        if (total <= 0L) {
            return cap;
        }
        long remaining = total - streamingBudgetSpentNs;
        if (remaining <= 0L) {
            return 0L;
        }
        return Math.min(cap, remaining);
    }

    public static void consumeStreamingBudget(long elapsedNs) {
        if (elapsedNs > 0L) {
            streamingBudgetSpentNs += elapsedNs;
        }
    }

    public static boolean isStreamingBudgetExhausted() {
        return streamingBudgetTotalNs > 0L && streamingBudgetSpentNs >= streamingBudgetTotalNs;
    }

    public static void recordLightRan() {
        consecutiveDeferredLightFrames = 0;
    }

    public boolean isMovingFast() {
        return this.smoothedSpeedBps > FAST_MOVEMENT_THRESHOLD_BPS;
    }

    public boolean isTurningFast() {
        return this.smoothedTurnDegPerSec > FAST_TURN_THRESHOLD_DPS;
    }

    public void updateCameraMotion(
            double camX,
            double camY,
            double camZ,
            float yaw,
            float pitch,
            long nowNs
    ) {
        long prevNs = this.prevCamNs;
        if (!Double.isNaN(this.prevCamX) && prevNs != 0L) {
            long dtNs = nowNs - prevNs;
            if (dtNs > 0L) {
                double dtSec = dtNs * 1.0E-9D;
                double dx = camX - this.prevCamX;
                double dy = camY - this.prevCamY;
                double dz = camZ - this.prevCamZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < 256.0D * 256.0D) {
                    if (dtSec > 0.5D) {
                        this.smoothedSpeedBps = Math.sqrt(distSq) / dtSec;
                    } else if (dtSec >= 1.0E-4D) {
                        double speedBps = Math.sqrt(distSq) / dtSec;
                        // Framerate-independent EWMA (~100 ms time constant across 60–240 FPS)
                        double alpha = dtSec / (dtSec + 0.095D);
                        this.smoothedSpeedBps += alpha * (speedBps - this.smoothedSpeedBps);
                    }
                }
                if (!Float.isNaN(this.prevCamYaw)) {
                    double dYaw = yaw - this.prevCamYaw;
                    dYaw = ((dYaw + 180.0D) % 360.0D + 360.0D) % 360.0D - 180.0D;
                    double dPitch = pitch - this.prevCamPitch;
                    double deg = Math.hypot(dYaw, dPitch);
                    if (dtSec >= 1.0E-4D && dtSec <= 0.5D && deg < 180.0D) {
                        double turnDps = deg / dtSec;
                        double alpha = turnDps > this.smoothedTurnDegPerSec
                                ? dtSec / (dtSec + 0.035D)
                                : dtSec / (dtSec + 0.140D);
                        this.smoothedTurnDegPerSec += alpha * (turnDps - this.smoothedTurnDegPerSec);
                    } else if (dtSec > 0.5D) {
                        this.smoothedTurnDegPerSec = 0.0D;
                    }
                }
            }
        }
        this.prevCamX = camX;
        this.prevCamY = camY;
        this.prevCamZ = camZ;
        this.prevCamYaw = yaw;
        this.prevCamPitch = pitch;
        this.prevCamNs = nowNs;
    }

    public void onWorldReset() {
        FrameGuard.get().resetSession();
        com.chunking.engine.core.LiveStats.get().resetSession();
        FrameGuard.get().notifyWorldLoadStart();
        this.prevCamX = Double.NaN;
        this.prevCamY = Double.NaN;
        this.prevCamZ = Double.NaN;
        this.prevCamYaw = Float.NaN;
        this.prevCamPitch = Float.NaN;
        this.prevCamNs = 0L;
        this.smoothedSpeedBps = 0.0D;
        this.smoothedTurnDegPerSec = 0.0D;
        streamingBudgetTotalNs = INITIAL_WORLD_LOAD_BUDGET_NS;
        streamingBudgetSpentNs = 0L;
        consecutiveDeferredLightFrames = 0;
        lastRenderFrameNs = 0L;
        localFrameEwmaNs = 16_666_667L;
        localSpikeCooldownFrames = 0;
        guiTransitionUntilNs = 0L;
    }

    /**
     * Widens Minecraft's render distance {@link net.minecraft.client.OptionInstance} range and codec
     * to {@link ModConfig#maxSliderRenderDistance()} so values above 32 chunks can be selected
     * and loaded from {@code options.txt}.
     */
    public static void applyWidenedRenderDistanceRange(net.minecraft.client.OptionInstance<Integer> renderDistance) {
        if (renderDistance == null) {
            return;
        }
        int maxRadius = ModConfig.maxSliderRenderDistance();
        net.minecraft.client.OptionInstance.IntRange widenedRange =
                new net.minecraft.client.OptionInstance.IntRange(2, maxRadius, false);
        @SuppressWarnings("unchecked")
        com.chunking.engine.mixin.client.accessor.OptionInstanceAccessor<Integer> accessor =
                (com.chunking.engine.mixin.client.accessor.OptionInstanceAccessor<Integer>) (Object) renderDistance;
        accessor.chunking$setValues(widenedRange);
        accessor.chunking$setCodec(widenedRange.codec());
        Integer current = renderDistance.get();
        if (current != null && current > maxRadius) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.levelRenderer != null) {
                renderDistance.set(maxRadius);
            }
        }
    }

    public static void refreshRenderDistanceSlider() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            applyWidenedRenderDistanceRange(mc.options.renderDistance());
        }
    }
}
