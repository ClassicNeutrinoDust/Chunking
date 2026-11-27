package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.scheduler.FrameGuard;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps lighting batches responsive (64 tasks/batch instead of javac-inlined 1000) and schedules
 * follow-up light updates immediately after both {@code addTask} and {@code runUpdate} so chunks
 * never stall waiting at {@code INITIALIZE_LIGHT}/{@code LIGHT} on main-thread ticks.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightEngineMixin {
    @Shadow
    @Final
    private ObjectList<?> lightTasks;

    @Shadow
    protected abstract void tryScheduleUpdate();

    @ModifyConstant(method = {"runUpdate", "lambda$addTask$0"}, constant = @Constant(intValue = 1000), require = 0)
    private int chunking$lowerInlinedLightBatchThreshold(int vanillaBatch) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return vanillaBatch;
        }
        return FrameGuard.get().hasActiveClientFrames()
                ? 32
                : (ModConfig.WORLDGEN_WORKERS >= 16 ? 512 : 256);
    }

    @Dynamic
    @Inject(method = "lambda$addTask$0", at = @At("TAIL"), require = 0)
    private void chunking$scheduleLightUpdateAfterAddTask(CallbackInfo ci) {
        if (ModConfig.PARALLEL_STATUS_TASKS && !this.lightTasks.isEmpty()) {
            this.tryScheduleUpdate();
        }
    }

    @Dynamic
    @Inject(method = "lambda$tryScheduleUpdate$0", at = @At("RETURN"), require = 0)
    private void chunking$chainLightScheduleUpdate(CallbackInfo ci) {
        if (ModConfig.PARALLEL_STATUS_TASKS) {
            this.tryScheduleUpdate();
        }
    }
}
