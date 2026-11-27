package com.chunking.engine.scheduler;

/**
 * Package-private test bridge into {@link FrameGuard} frame/step state.
 */
public final class FrameGuardProbe {
    private FrameGuardProbe() {}

    public static void init(int maxWorkers) {
        FrameGuard.get().stop();
        FrameGuard.get().init(maxWorkers, s -> {});
    }

    public static void feedFrame(double ms) {
        FrameGuard.get().testFrame(ms);
    }

    public static void step() {
        FrameGuard.get().step();
    }

    public static void reset() {
        FrameGuard.get().stop();
    }
}
