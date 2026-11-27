package com.chunking.engine.mixin;

import com.chunking.engine.access.CacheAllInCellAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes cached voxel values on {@code NoiseChunk$CacheAllInCell} for O(1) sky-air cell detection.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public abstract class NoiseCacheAllInCellMixin implements CacheAllInCellAccess {
    @Shadow
    @Final
    private DensityFunction noiseFiller;

    @Shadow
    @Final
    private double[] values;

    @Override
    public double[] chunking$cellValues() {
        return this.values;
    }

    @Override
    public DensityFunction chunking$cellNoiseFiller() {
        return this.noiseFiller;
    }
}
