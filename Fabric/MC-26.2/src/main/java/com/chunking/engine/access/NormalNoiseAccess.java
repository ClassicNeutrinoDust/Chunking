package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Direct unboxed evaluation bridge onto {@code NormalNoise}.
 */
public interface NormalNoiseAccess {
    double chunking$sampleNormalFast(double x, double y, double z);

    void chunking$sampleColumnFast(double xFixed, int[] by, double yScale, double zFixed, double[] out, int count);

    void chunking$sampleColumnRange(double xFixed, int[] by, int startIdx, double yScale, double zFixed, double[] out, int totalCount);

    void chunking$sampleBatch3DInt(int[] bx, int[] by, int[] bz, double xzScale, double yScale, double[] out, int count);

    ImprovedNoise[] chunking$fusedOctaves();

    double[] chunking$fusedInputFactors();

    double[] chunking$fusedScales();
}
