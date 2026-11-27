package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates first-open FBO/post-chain GPU blur hitches and triggers a brief GUI-transition
 * grace window when opening inventory, container, and pause screens while in-game.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Shadow
    @Final
    protected Minecraft minecraft;

    @Inject(method = "added", at = @At("HEAD"), require = 0)
    private void chunking$onScreenAdded(CallbackInfo ci) {
        if (this.minecraft != null && this.minecraft.level != null) {
            ClientRuntime.notifyGuiTransition();
        }
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void chunking$skipInGameScreenBlur(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
        if (ModConfig.REDUCE_SCREEN_BLUR && this.minecraft != null && this.minecraft.level != null) {
            ci.cancel();
        }
    }
}
