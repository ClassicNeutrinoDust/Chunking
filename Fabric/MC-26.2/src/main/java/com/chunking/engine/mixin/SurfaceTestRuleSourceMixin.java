package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.world.FastSurface;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Algebraically eliminates dead {@code TestRule} branches when {@code ifTrue} is constant-false or
 * {@code thenRun} is never-matching, and unwraps {@code TestRule} when {@code ifTrue} is constant-true.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$TestRuleSource")
public abstract class SurfaceTestRuleSourceMixin {
    @Shadow
    @Final
    private SurfaceRules.ConditionSource ifTrue;

    @Shadow
    @Final
    private SurfaceRules.RuleSource thenRun;

    @WrapMethod(method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;")
    private @Coerce Object chunking$pruneDeadTestRule(@Coerce Object context, Operation<Object> original) {
        if (!ModConfig.FAST_SURFACE_RULES
                || (!FastSurface.canBuildTestRule() && !FastSurface.canCompileCondition())) {
            return original.call(context);
        }
        Object cond = FastSurface.evalConditionSource(this.ifTrue, context);
        if (FastSurface.isAlwaysFalse(cond)) {
            return FastSurface.NEVER_MATCH_RULE;
        }
        Object followup = FastSurface.evalRuleSource(this.thenRun, context);
        if (followup == null || FastSurface.isNeverMatch(followup)) {
            return FastSurface.NEVER_MATCH_RULE;
        }
        if (FastSurface.isAlwaysTrue(cond)) {
            return followup;
        }
        return FastSurface.newTestRule(cond, followup);
    }
}
