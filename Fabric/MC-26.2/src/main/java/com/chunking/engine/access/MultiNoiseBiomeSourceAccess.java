package com.chunking.engine.access;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Exposes the parameter list backing {@code MultiNoiseBiomeSource} for column-first biome generation.
 */
public interface MultiNoiseBiomeSourceAccess {
    Climate.ParameterList<Holder<Biome>> chunking$parameters();
}
