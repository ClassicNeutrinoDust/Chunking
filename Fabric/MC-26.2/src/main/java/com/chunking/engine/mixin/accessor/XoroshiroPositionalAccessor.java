package com.chunking.engine.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes internal 128-bit state of {@code XoroshiroRandomSource.XoroshiroPositionalRandomFactory}
 * for zero-allocation {@code VerticalGradientCondition} RNG checks.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.XoroshiroRandomSource$XoroshiroPositionalRandomFactory")
public interface XoroshiroPositionalAccessor {
    @Accessor("seedLo")
    long chunking$getSeedLo();

    @Accessor("seedHi")
    long chunking$getSeedHi();
}
