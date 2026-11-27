package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Exposes raw cell values and inner density function from {@code NoiseChunk$CacheAllInCell}.
 */
public interface CacheAllInCellAccess {
    double[] chunking$cellValues();

    DensityFunction chunking$cellNoiseFiller();
}
