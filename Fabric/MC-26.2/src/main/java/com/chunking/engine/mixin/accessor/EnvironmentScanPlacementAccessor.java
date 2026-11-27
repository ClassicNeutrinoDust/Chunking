package com.chunking.engine.mixin.accessor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EnvironmentScanPlacement.class)
public interface EnvironmentScanPlacementAccessor {
    @Accessor("directionOfSearch")
    Direction chunking$getDirectionOfSearch();

    @Accessor("targetCondition")
    BlockPredicate chunking$getTargetCondition();

    @Accessor("allowedSearchCondition")
    BlockPredicate chunking$getAllowedSearchCondition();

    @Accessor("maxSteps")
    int chunking$getMaxSteps();
}
