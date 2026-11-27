package com.chunking.engine.mixin;

import com.chunking.engine.access.ClimateNodeAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Leaf")
public abstract class ClimateLeafMixin<T> implements ClimateNodeAccess {
    @Shadow
    @Final
    private T value;

    @Override
    public Object chunking$leafValue() {
        return this.value;
    }
}
