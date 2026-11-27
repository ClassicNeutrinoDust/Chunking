package com.chunking.engine.mixin.accessor;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CountOnEveryLayerPlacement.class)
public interface CountOnEveryLayerAccessor {
    @Accessor("count")
    IntProvider chunking$getCount();
}
