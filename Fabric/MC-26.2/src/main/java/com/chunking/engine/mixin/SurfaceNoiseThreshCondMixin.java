package com.chunking.engine.mixin;

import com.chunking.engine.access.SurfaceConditionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes public {@link SurfaceConditionAccess} bridge on package-private {@code 1NoiseThresholdCondition}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$NoiseThresholdConditionSource$1NoiseThresholdCondition")
public abstract class SurfaceNoiseThreshCondMixin implements SurfaceConditionAccess {
    @Shadow
    public abstract boolean test();

    @Override
    public boolean chunking$test() {
        return this.test();
    }
}
