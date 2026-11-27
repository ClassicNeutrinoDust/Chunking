package com.chunking.engine.access;

/**
 * Direct unboxed evaluation bridge onto {@code BlendedNoise}.
 */
public interface BlendedNoiseAccess {
    double chunking$computeFast(int bx, int by, int bz);

    void chunking$evalColumnFast(int bx, int[] by, int bz, double[] out, int count);

    void chunking$evalColumnRange(int bx, int[] by, int startIdx, int bz, double[] out, int totalCount);
}
