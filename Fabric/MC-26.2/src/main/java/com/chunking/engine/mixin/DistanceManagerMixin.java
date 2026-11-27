package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.PlayerTicketAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import net.minecraft.util.thread.TaskScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.Executor;

/**
 * Widens PlayerTicketTracker max distance past 32 and dispatches player tickets synchronously.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @WrapOperation(
            method = {"addPlayer", "removePlayer"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;update(JIZ)V"
            ),
            require = 0
    )
    private void chunking$markPlayerTicketTrackerDirty(
            @Coerce ChunkTracker instance,
            long pos,
            int level,
            boolean isDecreasing,
            Operation<Void> original
    ) {
        if (instance instanceof PlayerTicketAccess acc) {
            acc.chunking$markDirty();
            return;
        }
        original.call(instance, pos, level, isDecreasing);
    }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 32))
    private int chunking$widenPlayerTicketTrackerMaxDistance(int vanillaMax) {
        return Math.max(vanillaMax, ModConfig.maxPlayerTicketDistance());
    }

    @ModifyVariable(method = "updatePlayerTickets", at = @At("HEAD"), argsOnly = true)
    private int chunking$clampPlayerTicketViewDistance(int viewDistance) {
        return Math.max(2, Math.min(ModConfig.maxPlayerTicketDistance(), viewDistance));
    }

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/util/thread/TaskScheduler;Ljava/util/concurrent/Executor;I)Lnet/minecraft/server/level/ThrottlingChunkTaskDispatcher;"
            )
    )
    private ThrottlingChunkTaskDispatcher chunking$uncapPlayerTicketThrottle(
            TaskScheduler<Runnable> executor,
            Executor dispatchExecutor,
            int maxConcurrentChunks,
            Operation<ThrottlingChunkTaskDispatcher> original
    ) {
        int workers = Math.max(1, ModConfig.WORLDGEN_WORKERS);
        if (ModConfig.isClientEnvironment()) {
            int paced = Math.max(6, Math.min(32, workers * 3));
            return original.call(executor, dispatchExecutor, paced);
        }
        int paced = Math.max(6, Math.min(512, workers * 4));
        return original.call(executor, (Executor) Runnable::run, paced);
    }
}
