package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards {@link OreVeinifier#create} with an immediate {@code blockY} range check
 * ({@code [-60, -8]} or {@code [0, 50]}) before evaluating {@code veinToggle}, skipping
 * redundant ore noise evaluations on ~55% of non-air blocks.
 */
@Mixin(OreVeinifier.class)
public abstract class OreVeinifierMixin {
    @Inject(method = "create", at = @At("RETURN"), cancellable = true)
    private static void chunking$guardOreVeinYRange(
            DensityFunction veinToggle,
            DensityFunction veinRidged,
            DensityFunction veinGap,
            PositionalRandomFactory oreRandom,
            CallbackInfoReturnable<NoiseChunk.BlockStateFiller> cir
    ) {
        if (!ModConfig.SIMD_NOISE) {
            return;
        }
        NoiseChunk.BlockStateFiller delegate = cir.getReturnValue();
        BlockState fallback = SharedConstants.DEBUG_ORE_VEINS ? Blocks.AIR.defaultBlockState() : null;
        cir.setReturnValue(context -> {
            int y = context.blockY();
            if (com.chunking.engine.world.FastOreVeins.isOutsideOreVeinY(y)) {
                return fallback;
            }
            return delegate.calculate(context);
        });
    }
}
