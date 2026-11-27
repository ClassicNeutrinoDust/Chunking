package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Direct accessor bridge onto {@code NoiseChunk$FlatCache} so compiled JIT kernels read precomputed
 * quart-resolution columns without virtual dispatch or object allocation.
 */
public interface FlatCacheAccess {
    double chunking$sampleQuartFast(int bx, int bz);

    DensityFunction chunking$flatNoiseFiller();
}
