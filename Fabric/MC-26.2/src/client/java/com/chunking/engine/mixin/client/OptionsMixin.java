package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.config.ModConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unlocks the client render distance slider up to {@link ModConfig#FULL_CHUNK_RADIUS} (up to 64 chunks)
 * before {@link Options#load()} parses {@code options.txt}.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    @Final
    private OptionInstance<Integer> renderDistance;

    @Inject(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Options;load()V"
            )
    )
    private void chunking$unlockRenderDistanceSlider(CallbackInfo ci) {
        ClientRuntime.applyWidenedRenderDistanceRange(this.renderDistance);
    }
}
