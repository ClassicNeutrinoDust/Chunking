package com.chunking.engine.access;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Column-first zero-allocation biome sampler bridge on {@code Climate$Sampler}.
 */
public interface ClimateSamplerAccess {
    boolean chunking$fillBiomesColumnFast(ChunkAccess chunkAccess, ClimateRTreeAccess<Holder<Biome>> biomeTree);

    default boolean chunking$fillTerraBlenderBiomesColumnFast(ChunkAccess chunkAccess, Climate.ParameterList<?> tbParams) {
        return false;
    }

    default <T> T chunking$sampleAndSearchRaw(int qx, int qy, int qz, ClimateRTreeAccess<T> rtree) {
        return null;
    }

    default Holder<Biome> chunking$sampleAndSearchTerraBlenderRaw(int qx, int qy, int qz, Climate.ParameterList<?> tlParams) {
        return null;
    }

    default boolean chunking$isErosionYInvariant() {
        return false;
    }

    default boolean chunking$isDepthConstY() {
        return false;
    }
}
