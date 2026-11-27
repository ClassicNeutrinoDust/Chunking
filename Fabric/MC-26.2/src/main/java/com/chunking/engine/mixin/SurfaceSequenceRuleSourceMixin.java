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

import java.util.List;

/**
 * Flattens nested {@code SurfaceRules.SequenceRuleSource} trees per chunk, drops constant
 * non-matching rules, and truncates unreachable rules after any unconditional {@code StateRule}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource")
public abstract class SurfaceSequenceRuleSourceMixin {
    @Shadow
    @Final
    private List<SurfaceRules.RuleSource> sequence;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$buildPrunedFlatSequence(@Coerce Object context, CallbackInfoReturnable cir) {
        if (!ModConfig.FAST_SURFACE_RULES) {
            return;
        }
        cir.setReturnValue(FastSurface.buildFlattenedSequence(this.sequence, context));
    }
}
