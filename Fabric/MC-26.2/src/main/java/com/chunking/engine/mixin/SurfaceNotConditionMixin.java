package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.world.FastSurface;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Constant-folds {@code SurfaceRules.not(...)} when its inner condition evaluates to a known
 * chunk-constant false or true sentinel.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$NotConditionSource")
public abstract class SurfaceNotConditionMixin {
    @Shadow
    @Final
    private SurfaceRules.ConditionSource target;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$Condition;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$foldConstantNot(@Coerce Object context, CallbackInfoReturnable cir) {
        if (!ModConfig.FAST_SURFACE_RULES || !FastSurface.canBuildNotCondition()) {
            return;
        }
        Object inner = FastSurface.evalConditionSource(this.target, context);
        Object folded = FastSurface.foldNotCondition(inner);
        cir.setReturnValue(folded != null ? folded : FastSurface.newNotCondition(inner));
    }
}
