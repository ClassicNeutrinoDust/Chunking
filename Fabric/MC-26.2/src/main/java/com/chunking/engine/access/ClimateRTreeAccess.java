package com.chunking.engine.access;

/**
 * Zero-allocation primitive R-tree search and column-batch bridge for quantized 6D climate coordinates.
 */
public interface ClimateRTreeAccess<T> {
    T chunking$searchRaw(long tQ, long hQ, long cQ, long eQ, long dQ, long wQ);

    boolean chunking$supportsColumnBatch();

    void chunking$searchColumnBatch(
            long tQ, long hQ, long cQ, long eQ, long wQ,
            long[] dQs, int count,
            Object[] outBiomes, int outOffset, int outStride
    );
}
