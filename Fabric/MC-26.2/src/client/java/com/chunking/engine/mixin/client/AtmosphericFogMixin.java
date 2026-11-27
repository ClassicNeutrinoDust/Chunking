package com.chunking.engine.mixin.client;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.MixinPlugin;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scales atmospheric fog distances in The Nether (96-block vanilla cap), The End (96-block Ender Dragon
 * boss-fog cap), and >32/>64-chunk Overworld views so loaded chunks and Sodium's fog occlusion are not
 * clipped to 6 chunks. Underwater, lava, powder-snow, blindness, and darkness fog remain vanilla.
 */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void chunking$scaleDimensionFogWithRenderDistance(
            FogData fogData,
            Camera camera,
            ClientLevel level,
            float renderDistanceBlocks,
            DeltaTracker deltaTracker,
            CallbackInfo ci
    ) {
        if (!ModConfig.SCALE_DIMENSION_FOG || fogData == null || level == null || MixinPlugin.isLodModLoaded()) {
            return;
        }
        if ((level.dimension() == Level.NETHER
                || level.dimension() == Level.END
                || (level.dimensionType().hasCeiling() && fogData.environmentalEnd <= 96.5F))
                && renderDistanceBlocks > 96.0F
                && fogData.environmentalEnd < renderDistanceBlocks) {
            fogData.environmentalStart = Math.max(fogData.environmentalStart, renderDistanceBlocks * 0.25F);
            fogData.environmentalEnd = Math.max(fogData.environmentalEnd, renderDistanceBlocks);
            fogData.skyEnd = Math.max(fogData.skyEnd, renderDistanceBlocks);
            fogData.cloudEnd = Math.max(fogData.cloudEnd, renderDistanceBlocks);
        }
        if (renderDistanceBlocks > 512.0F && fogData.skyEnd >= 511.5F && fogData.skyEnd < renderDistanceBlocks) {
            fogData.skyEnd = renderDistanceBlocks;
        }
        if (fogData.environmentalEnd > 1.0F && fogData.environmentalStart > fogData.environmentalEnd - 1.0F) {
            fogData.environmentalStart = fogData.environmentalEnd - 1.0F;
        }
    }
}
