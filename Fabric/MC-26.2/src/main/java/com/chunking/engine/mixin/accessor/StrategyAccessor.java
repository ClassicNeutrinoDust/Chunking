package com.chunking.engine.mixin.accessor;

import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Strategy.class)
public interface StrategyAccessor {
    @Invoker("getConfigurationForPaletteSize")
    Configuration chunking$getConfigForPaletteSize(int paletteSize);
}
