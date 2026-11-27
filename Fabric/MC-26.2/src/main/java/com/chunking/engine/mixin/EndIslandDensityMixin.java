package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

/**
 * Thread-local 512-entry direct-mapped memoization cache for {@code EndIslandDensityFunction.compute},
 * eliminating ~99% of redundant 625-iteration {@code SimplexNoise.getValue} loops on shared {@code blockX/8, blockZ/8}
 * sample cells in The End.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$EndIslandDensityFunction")
public abstract class EndIslandDensityMixin {
    @Unique
    private static final int CHUNKING$CACHE_SIZE = 512;

    @Unique
    private static final int CHUNKING$CACHE_MASK = CHUNKING$CACHE_SIZE - 1;

    @Unique
    private static final class Cache {
        SimplexNoise lastNoise;
        final long[] keys = new long[CHUNKING$CACHE_SIZE];
        final double[] vals = new double[CHUNKING$CACHE_SIZE];

        Cache() {
            Arrays.fill(this.keys, Long.MIN_VALUE);
        }
    }

    @Unique
    private static final ThreadLocal<Cache> CHUNKING$TL_CACHE = ThreadLocal.withInitial(Cache::new);

    @Shadow
    @Final
    private SimplexNoise islandNoise;

    @WrapMethod(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D")
    private double chunking$memoizeEndIsland(
            DensityFunction.FunctionContext ctx,
            Operation<Double> original
    ) {
        if (!ModConfig.SIMD_NOISE) {
            return original.call(ctx);
        }
        // Must use signed Java division (/) matching vanilla idiv, NOT arithmetic right shift (>> 3).
        int sx = ctx.blockX() / 8;
        int sz = ctx.blockZ() / 8;
        long key = (((long) sx) << 32) | (sz & 0xFFFFFFFFL);
        Cache cache = CHUNKING$TL_CACHE.get();
        if (cache.lastNoise != this.islandNoise) {
            cache.lastNoise = this.islandNoise;
            Arrays.fill(cache.keys, Long.MIN_VALUE);
        }
        int slot = ((int) HashCommon.mix(key)) & CHUNKING$CACHE_MASK;
        if (cache.keys[slot] == key) {
            return cache.vals[slot];
        }
        double v = original.call(ctx);
        cache.keys[slot] = key;
        cache.vals[slot] = v;
        return v;
    }
}
