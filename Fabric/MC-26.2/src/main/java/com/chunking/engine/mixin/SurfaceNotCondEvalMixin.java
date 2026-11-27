package com.chunking.engine.mixin;

import com.chunking.engine.access.SurfaceConditionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes public {@link SurfaceConditionAccess} bridge on package-private {@code SurfaceRules$NotCondition}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$NotCondition")
public abstract class SurfaceNotCondEvalMixin implements SurfaceConditionAccess {
    @Shadow
    public abstract boolean test();

    @Override
    public boolean chunking$test() {
        return this.test();
    }
}
