package com.chunking.engine.mixin;

import com.chunking.engine.scheduler.TickBudget;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

/**
 * Tracks {@code BlockableEventLoop.managedBlock} depth on non-server loops (such as
 * {@code ServerChunkCache$MainThreadExecutor}) so {@code pollTask()} budgeting never starves
 * sync chunk waits, while skipping {@link MinecraftServer#waitUntilNextTick()} so between-tick
 * polling and sub-tick streaming remain active.
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {
    @WrapMethod(method = "managedBlock")
    private void chunking$trackManagedBlockDepth(BooleanSupplier stopCondition, Operation<Void> original) {
        if ((Object) this instanceof MinecraftServer) {
            original.call(stopCondition);
            return;
        }
        TickBudget.enterManagedBlock();
        try {
            original.call(stopCondition);
        } finally {
            TickBudget.exitManagedBlock();
        }
    }
}
