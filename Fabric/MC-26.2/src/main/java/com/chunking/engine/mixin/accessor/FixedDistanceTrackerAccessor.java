package com.chunking.engine.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker")
public interface FixedDistanceTrackerAccessor {
    @Accessor("chunks")
    Long2ByteMap chunking$getChunks();

    @Accessor("maxDistance")
    int chunking$getMaxDistance();
}
