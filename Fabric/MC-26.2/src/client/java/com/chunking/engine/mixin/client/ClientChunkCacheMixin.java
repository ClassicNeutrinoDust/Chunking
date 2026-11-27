package com.chunking.engine.mixin.client;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Records client chunk arrival telemetry for the live benchmark HUD.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void chunking$onClientChunkLoaded(
            int x,
            int z,
            FriendlyByteBuf buffer,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            com.chunking.engine.core.LiveStats.get().recordClientChunkLoaded();
        }
    }
}
