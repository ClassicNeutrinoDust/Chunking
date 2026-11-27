package com.chunking.engine.io;

/**
 * Per-thread unload save scope marker allowing {@code SerializableChunkData.copyOf} to skip
 * defensive {@code LevelChunkSection.copy()} allocations while serializing an unloading chunk
 * that is no longer reachable from live world maps.
 */
public final class UnloadSaveCtx {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private UnloadSaveCtx() {}

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }
}
