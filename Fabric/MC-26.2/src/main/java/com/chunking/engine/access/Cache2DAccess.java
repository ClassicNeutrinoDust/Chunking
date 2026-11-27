package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Direct accessor bridge onto {@code NoiseChunk$Cache2D} so compiled JIT kernels query or populate
 * the 64-entry L1 2D column cache without allocating {@code FunctionContext} objects.
 */
public interface Cache2DAccess {
    double chunking$sample2DFast(int bx, int bz);

    DensityFunction chunking$wrapped2DFunction();
}
