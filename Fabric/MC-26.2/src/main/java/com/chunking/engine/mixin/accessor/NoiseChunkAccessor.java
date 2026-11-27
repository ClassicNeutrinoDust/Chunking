package com.chunking.engine.mixin.accessor;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Exposes cell dimensions and interpolation state on {@link NoiseChunk} for fast separable and lazy trilinear interpolation.
 */
@Mixin(NoiseChunk.class)
public interface NoiseChunkAccessor {
    @Accessor("cellWidth")
    int chunking$getCellWidth();

    @Accessor("cellHeight")
    int chunking$getCellHeight();

    @Accessor("cellStartBlockX")
    int chunking$getCellStartBlockX();

    @Accessor("cellStartBlockY")
    int chunking$getCellStartBlockY();

    @Accessor("cellStartBlockZ")
    int chunking$getCellStartBlockZ();

    @Accessor("blender")
    Blender chunking$getBlender();

    @Accessor("firstNoiseX")
    int chunking$getFirstNoiseX();

    @Accessor("firstNoiseZ")
    int chunking$getFirstNoiseZ();

    @Accessor("cellNoiseMinY")
    int chunking$getCellNoiseMinY();

    @Accessor("inCellX")
    int chunking$getInCellX();

    @Accessor("inCellY")
    int chunking$getInCellY();

    @Accessor("inCellZ")
    int chunking$getInCellZ();

    @Accessor("interpolating")
    boolean chunking$isInterpolating();

    @Accessor("fillingCell")
    boolean chunking$isFillingCell();

    @Invoker("cachedClimateSampler")
    Climate.Sampler chunking$cachedClimateSampler(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget);
}
