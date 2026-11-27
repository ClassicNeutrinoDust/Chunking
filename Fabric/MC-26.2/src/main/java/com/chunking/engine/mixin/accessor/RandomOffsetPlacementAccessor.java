package com.chunking.engine.mixin.accessor;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RandomOffsetPlacement.class)
public interface RandomOffsetPlacementAccessor {
    @Accessor("xzSpread")
    IntProvider chunking$getXzSpread();

    @Accessor("ySpread")
    IntProvider chunking$getYSpread();
}
