package com.chunking.engine.mixin.accessor;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/**
 * Exposes current surface block coordinates, chunk biome set, and cached column biome
 * on {@code SurfaceRules.Context} without reflection.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public interface SurfaceContextAccessor {
    @Accessor("blockX")
    int chunking$getBlockX();

    @Accessor("blockY")
    int chunking$getBlockY();

    @Accessor("blockZ")
    int chunking$getBlockZ();

    @Accessor("possibleBiomes")
    Set<Holder<Biome>> chunking$getPossibleBiomes();

    @Accessor("lastUpdateXZ")
    long chunking$getLastUpdateXZ();

    @Accessor("lastUpdateY")
    long chunking$getLastUpdateY();

    @Invoker("getBiome")
    Holder<Biome> chunking$getBiome();
}
