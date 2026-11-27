package com.chunking.engine.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("promoteChunkMap")
    boolean chunking$invokePromoteChunkMap();

    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> chunking$getMainThreadExecutor();

    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> chunking$getPendingUnloads();

    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> chunking$getUpdatingChunkMap();

    @Accessor("activeChunkWrites")
    AtomicInteger chunking$getActiveChunkWrites();

    @Accessor("toDrop")
    LongSet chunking$getToDrop();
}
