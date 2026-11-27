package com.chunking.engine.mixin;

import com.chunking.engine.access.ClimateNodeAccess;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Node")
public abstract class ClimateNodeMixin implements ClimateNodeAccess {
    @Shadow
    @Final
    protected Climate.Parameter[] parameterSpace;

    @Shadow
    protected abstract long distance(long[] target);

    @Override
    public Climate.Parameter[] chunking$parameterSpace() {
        return this.parameterSpace;
    }

    @Override
    public long chunking$distanceFast(long[] target) {
        return this.distance(target);
    }
}
