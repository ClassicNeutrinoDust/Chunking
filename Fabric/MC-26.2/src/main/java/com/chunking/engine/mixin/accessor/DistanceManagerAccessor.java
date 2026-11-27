package com.chunking.engine.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
    @Accessor("playersPerChunk")
    Long2ObjectMap<ObjectSet<ServerPlayer>> chunking$getPlayersPerChunk();

    @Accessor("ticketDispatcher")
    ThrottlingChunkTaskDispatcher chunking$getTicketDispatcher();
}
