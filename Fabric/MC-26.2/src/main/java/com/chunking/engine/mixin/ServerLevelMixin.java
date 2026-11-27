package com.chunking.engine.mixin;

import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.mixin.accessor.LevelThreadAccessor;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.TaskQueue;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.scheduler.MoveTracker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Replaces Vanilla's unbounded {@code maxTicks = 65536} in {@link ServerLevel#tick} calls to
 * {@code LevelTicks.tick(long, int, BiConsumer)} with an adaptive per-tick budget ({@code 96}
 * under severe MSPT &gt; 35 ms, {@code 160} under mild pressure &gt; 25 ms, {@code 256} during
 * initial load, active worldgen, or flight, and {@code 768} when idle) so leaf-update cascades
 * and post-flight fluid/leaf backlogs never spike MSPT.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @ModifyArg(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V"
            ),
            index = 1,
            require = 0
    )
    private int chunking$budgetScheduledTicksPerServerTick(int vanillaMaxTicks) {
        FrameGuard gov = FrameGuard.get();
        double lastTickMs = TickBudget.lastRecordedTickMs();
        if (lastTickMs > 35.0D) {
            return Math.min(vanillaMaxTicks, 96);
        }
        if (lastTickMs > 25.0D || gov.isServerTickPressured()) {
            return Math.min(vanillaMaxTicks, 160);
        }
        MoveTracker.PlayerMotion[] motions =
                ChunkEngine.get().velocityTracker().getAnyActiveMotions();
        if (motions != null) {
            for (int i = 0; i < motions.length; i++) {
                MoveTracker.PlayerMotion m = motions[i];
                if (m != null && m.speedXZ >= 0.65D) {
                    return Math.min(vanillaMaxTicks, 256);
                }
            }
        }
        if (gov.isInitialWorldLoadWindow()
                || TaskQueue.inFlightCount() > 0
                || lastTickMs > 16.0D) {
            return Math.min(vanillaMaxTicks, 256);
        }
        return Math.min(vanillaMaxTicks, 768);
    }

    @WrapOperation(
            method = "getNextEntityId",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;hasEntityWithId(I)Z"
            ),
            require = 0
    )
    private boolean chunking$safeHasEntityWithId(
            ServerChunkCache instance,
            int entityId,
            Operation<Boolean> original
    ) {
        if (Thread.currentThread() != ((LevelThreadAccessor) (Object) this).chunking$getThread()) {
            return false;
        }
        return original.call(instance, entityId);
    }
}
