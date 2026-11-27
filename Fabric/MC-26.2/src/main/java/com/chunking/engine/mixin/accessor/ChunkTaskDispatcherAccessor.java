package com.chunking.engine.mixin.accessor;

import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkTaskDispatcher.class)
public interface ChunkTaskDispatcherAccessor {
    @Accessor("queue")
    ChunkTaskPriorityQueue chunking$getQueue();

    @Invoker("popTasks")
    @Nullable ChunkTaskPriorityQueue.TasksForChunk chunking$invokePopTasks();
}
