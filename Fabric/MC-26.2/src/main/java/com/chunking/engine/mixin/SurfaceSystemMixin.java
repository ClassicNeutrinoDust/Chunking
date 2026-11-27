package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.NormalNoiseAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Batch-evaluates surface noise per chunk and fast-paths empty fluids and common stone states.
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemMixin {
    @Shadow
    @Final
    private BlockState defaultBlock;

    @Shadow
    @Final
    private NormalNoise surfaceNoise;

    @Shadow
    @Final
    private NormalNoise surfaceSecondaryNoise;

    @Unique
    private static final FluidState CHUNKING$EMPTY_FLUID_STATE = Fluids.EMPTY.defaultFluidState();

    @Unique
    private static final BlockState CHUNKING$AIR_STATE = Blocks.AIR.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$WATER_STATE = Blocks.WATER.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$CAVE_AIR_STATE = Blocks.CAVE_AIR.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$LAVA_STATE = Blocks.LAVA.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$STONE_STATE = Blocks.STONE.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$DEEPSLATE_STATE = Blocks.DEEPSLATE.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$NETHERRACK_STATE = Blocks.NETHERRACK.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$END_STONE_STATE = Blocks.END_STONE.defaultBlockState();

    @Unique
    private static final class SurfaceNoiseCache {
        Object owner;
        int chunkX;
        int chunkZ;
        boolean active;
        boolean secondaryComputed;
        final int[] bx = new int[256];
        final int[] by = new int[256];
        final int[] bz = new int[256];
        final double[] primaryVals = new double[256];
        final double[] secondaryVals = new double[256];
    }

    @Unique
    private static final ThreadLocal<SurfaceNoiseCache> CHUNKING$SURFACE_NOISE_CACHE =
            ThreadLocal.withInitial(SurfaceNoiseCache::new);

    // Non-static @Unique fields must have no inline initializers (JEP 513 / Mixin 0.8.7).
    @Unique
    private BlockState chunking$stoneDefaultBlock;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initStoneDefaultBlock(CallbackInfo ci) {
        BlockState def = this.defaultBlock;
        this.chunking$stoneDefaultBlock = (def != null && !def.isAir() && def.getFluidState().isEmpty()) ? def : null;
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "buildSurface")
    private void chunking$wrapBuildSurfaceWithNoiseCache(
            RandomState randomState,
            BiomeManager biomeManager,
            boolean useLegacyRandom,
            WorldGenerationContext generationContext,
            ChunkAccess protoChunk,
            NoiseChunk noiseChunk,
            SurfaceRules.RuleSource ruleSource,
            Set<Holder<Biome>> possibleBiomes,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original
    ) {
        SurfaceNoiseCache cache = CHUNKING$SURFACE_NOISE_CACHE.get();
        cache.active = false;
        cache.secondaryComputed = false;
        cache.owner = null;
        if (ModConfig.SIMD_NOISE
                && ModConfig.FAST_SURFACE_RULES
                && protoChunk != null
                && this.surfaceNoise != null
                && this.surfaceSecondaryNoise != null) {
            int baseX = protoChunk.getPos().getMinBlockX();
            int baseZ = protoChunk.getPos().getMinBlockZ();
            int[] bx = cache.bx;
            int[] bz = cache.bz;
            int idx = 0;
            for (int lz = 0; lz < 16; lz++) {
                int wz = baseZ + lz;
                for (int lx = 0; lx < 16; lx++) {
                    bx[idx] = baseX + lx;
                    bz[idx] = wz;
                    idx++;
                }
            }
            ((NormalNoiseAccess) (Object) this.surfaceNoise)
                    .chunking$sampleBatch3DInt(bx, cache.by, bz, 1.0D, 0.0D, cache.primaryVals, 256);
            cache.owner = this;
            cache.chunkX = baseX >> 4;
            cache.chunkZ = baseZ >> 4;
            cache.active = true;
        }
        try {
            original.call(randomState, biomeManager, useLegacyRandom, generationContext, protoChunk, noiseChunk, ruleSource, possibleBiomes);
        } finally {
            cache.active = false;
            cache.secondaryComputed = false;
            cache.owner = null;
        }
    }

    @Redirect(
            method = "buildSurface",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"
            ),
            require = 0
    )
    private boolean chunking$fastFluidIsEmpty(FluidState fluidState) {
        return fluidState == CHUNKING$EMPTY_FLUID_STATE || fluidState.isEmpty();
    }

    @Redirect(
            method = "getSurfaceDepth",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/synth/NormalNoise;getValue(DDD)D"
            ),
            require = 1
    )
    private double chunking$cachedSurfaceDepthNoise(NormalNoise instance, double x, double y, double z) {
        SurfaceNoiseCache cache = CHUNKING$SURFACE_NOISE_CACHE.get();
        if (cache.active && cache.owner == this && instance == this.surfaceNoise && y == 0.0D) {
            int ix = (int) x;
            int iz = (int) z;
            if ((double) ix == x && (double) iz == z
                    && (ix >> 4) == cache.chunkX
                    && (iz >> 4) == cache.chunkZ) {
                return cache.primaryVals[((iz & 15) << 4) | (ix & 15)];
            }
        }
        return instance.getValue(x, y, z);
    }

    @Redirect(
            method = "getSurfaceSecondary",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/synth/NormalNoise;getValue(DDD)D"
            ),
            require = 1
    )
    private double chunking$cachedSurfaceSecondaryNoise(NormalNoise instance, double x, double y, double z) {
        SurfaceNoiseCache cache = CHUNKING$SURFACE_NOISE_CACHE.get();
        if (cache.active && cache.owner == this && instance == this.surfaceSecondaryNoise && y == 0.0D) {
            int ix = (int) x;
            int iz = (int) z;
            if ((double) ix == x && (double) iz == z
                    && (ix >> 4) == cache.chunkX
                    && (iz >> 4) == cache.chunkZ) {
                if (!cache.secondaryComputed) {
                    ((NormalNoiseAccess) (Object) this.surfaceSecondaryNoise)
                            .chunking$sampleBatch3DInt(cache.bx, cache.by, cache.bz, 1.0D, 0.0D, cache.secondaryVals, 256);
                    cache.secondaryComputed = true;
                }
                return cache.secondaryVals[((iz & 15) << 4) | (ix & 15)];
            }
        }
        return instance.getValue(x, y, z);
    }

    /**
     * @author Chunking
     * @reason Zero-alloc fast path for dimension defaultBlock and dominant worldgen block states inside per-column surface sweeps.
     */
    @Overwrite
    private boolean isStone(BlockState state) {
        if (state == this.chunking$stoneDefaultBlock && state != null) {
            return true;
        }
        if (ModConfig.FAST_SURFACE_RULES) {
            if (state == CHUNKING$AIR_STATE
                    || state == CHUNKING$WATER_STATE
                    || state == CHUNKING$CAVE_AIR_STATE
                    || state == CHUNKING$LAVA_STATE) {
                return false;
            }
            if (state == CHUNKING$DEEPSLATE_STATE
                    || state == CHUNKING$STONE_STATE
                    || state == CHUNKING$NETHERRACK_STATE
                    || state == CHUNKING$END_STONE_STATE) {
                return true;
            }
        }
        if (state.isAir()) {
            return false;
        }
        FluidState fluid = state.getFluidState();
        return fluid == CHUNKING$EMPTY_FLUID_STATE || fluid.isEmpty();
    }
}
