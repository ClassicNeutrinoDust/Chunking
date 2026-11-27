package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.world.FastSurface;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Eliminates ~340 MB/12s of {@code XoroshiroRandomSource} + {@code Xoroshiro128PlusPlus} allocations
 * inside {@code SurfaceRules.VerticalGradientConditionSource$1VerticalGradientCondition.compute()}
 * with zero wrapper lambda allocations.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition")
public abstract class VerticalGradientMixin {
    @Shadow
    @Final
    int val$trueAtAndBelow;

    @Shadow
    @Final
    int val$falseAtAndAbove;

    @Shadow
    @Final
    PositionalRandomFactory val$randomFactory;

    /**
     * @author Chunking
     * @reason Inline Xoroshiro128++ positional vertical gradient check without allocating XoroshiroRandomSource or lambdas.
     */
    @Overwrite
    protected boolean compute() {
        return FastSurface.evalVerticalGradient(
                this,
                this.val$trueAtAndBelow,
                this.val$falseAtAndAbove,
                this.val$randomFactory,
                ModConfig.FAST_SURFACE_RULES && FastSurface.canFastEvalVerticalGradient()
        );
    }
}
