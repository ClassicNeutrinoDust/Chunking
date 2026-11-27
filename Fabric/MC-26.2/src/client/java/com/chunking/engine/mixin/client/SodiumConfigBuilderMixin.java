package com.chunking.engine.mixin.client;

import com.chunking.engine.config.ModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Unlocks Sodium's Video Settings Render Distance slider beyond 32 chunks when Sodium is installed.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder", remap = false)
public abstract class SodiumConfigBuilderMixin {
    @ModifyArg(
            method = "buildGeneralPage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;setRange(III)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
                    ordinal = 0,
                    remap = false
            ),
            index = 1,
            require = 0,
            remap = false
    )
    private int chunking$unlockSodiumRenderDistanceSlider(int max) {
        return max == 32 ? ModConfig.maxSliderRenderDistance() : max;
    }
}
