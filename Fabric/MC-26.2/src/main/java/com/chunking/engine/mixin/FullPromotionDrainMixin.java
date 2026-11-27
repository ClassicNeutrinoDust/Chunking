package com.chunking.engine.mixin;

import com.chunking.engine.scheduler.LoadPacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;

/**
 * Drains paced {@code ChunkStatusTasks.full} promotions on {@code ServerChunkCache$MainThreadExecutor}
 * during idle {@code pollTask()}, {@code managedBlock()} sync chunk waits, and shutdown {@code runAllTasks()}.
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class FullPromotionDrainMixin {
    @Inject(method = "pollTask", at = @At("RETURN"), cancellable = true)
    private void chunking$drainFullPromotions(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && LoadPacer.hasPending()) {
            if (LoadPacer.pollOne((Executor) (Object) this)) {
                cir.setReturnValue(Boolean.TRUE);
            }
        }
    }
}
