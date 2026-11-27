package com.chunking.engine.mixin.accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link PlacementFilter#shouldPlace} directly so {@link com.chunking.engine.mixin.PlacedFeatureMixin}
 * can evaluate filter modifiers without constructing per-modifier {@code Stream.of(pos).filter(...)} pipelines.
 */
@Mixin(PlacementFilter.class)
public interface PlacementFilterAccessor {
    @Invoker("shouldPlace")
    boolean chunking$shouldPlace(PlacementContext context, RandomSource random, BlockPos pos);
}
