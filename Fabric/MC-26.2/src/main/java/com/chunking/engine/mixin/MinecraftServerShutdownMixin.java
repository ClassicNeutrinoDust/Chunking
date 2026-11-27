package com.chunking.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps scheduleExecutables() draining chunk callbacks during shutdown saves to avoid deadlocks.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerShutdownMixin {
    @Unique
    private volatile boolean chunking$finishedSaving;

    @Inject(
            method = "stopServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void chunking$onFinishedSaving(CallbackInfo ci) {
        this.chunking$finishedSaving = true;
    }

    @Inject(
            method = "stopServer",
            at = @At("RETURN"),
            require = 0
    )
    private void chunking$onStopServerReturn(CallbackInfo ci) {
        this.chunking$finishedSaving = true;
    }

    @WrapOperation(
            method = "scheduleExecutables",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;isStopped()Z"
            ),
            require = 0
    )
    private boolean chunking$allowTasksUntilSaveCompletes(
            MinecraftServer instance,
            Operation<Boolean> original
    ) {
        return original.call(instance) && this.chunking$finishedSaving;
    }
}
