package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.core.StageTimer;
import com.chunking.engine.scheduler.LoadPacer;
import com.chunking.engine.scheduler.TaskQueue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ChunkStatusTasks.class)
public abstract class StatusTasksMixin {
    @Unique
    private static final Logger CHUNKING$LOGGER = LoggerFactory.getLogger("Chunking");

    @Unique
    private static final ThreadLocal<Boolean> CHUNKING$IN_WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Unique
    private static CompletableFuture<ChunkAccess> chunking$dispatchWorkerStage(
            ChunkPos pos,
            int statusRank,
            StageTimer.Stage stage,
            Supplier<CompletableFuture<ChunkAccess>> taskCall
    ) {
        StageTimer profiler = ChunkEngine.get().stageProfiler();
        profiler.enterRealWorldgenTask();
        Executor pool = ChunkEngine.get().workerPool().executor();
        final CompletableFuture<ChunkAccess> stageFuture;
        try {
            stageFuture = TaskQueue.submit(pos.x(), pos.z(), statusRank, () -> {
                long t0 = System.nanoTime();
                CHUNKING$IN_WORKER.set(Boolean.TRUE);
                try {
                    return taskCall.get().join();
                } catch (Throwable t) {
                    CHUNKING$LOGGER.error("[Chunking] Worldgen stage {} (rank {}) failed at {}: ", stage, statusRank, pos, t);
                    throw t;
                } finally {
                    CHUNKING$IN_WORKER.set(Boolean.FALSE);
                    if (stage != null) {
                        profiler.record(stage, System.nanoTime() - t0);
                    }
                    profiler.exitRealWorldgenTask();
                }
            }, pool);
        } catch (Throwable t) {
            profiler.exitRealWorldgenTask();
            throw t;
        }
        TaskQueue.captureStageFuture(stageFuture);
        return stageFuture;
    }

    @Inject(method = "generateStructureStarts", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelStructureStarts(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        cir.setReturnValue(chunking$dispatchWorkerStage(chunk.getPos(), 1, StageTimer.Stage.STRUCTURE_STARTS,
                () -> ChunkStatusTasks.generateStructureStarts(context, step, cache, chunk)));
    }

    @Inject(method = "generateStructureReferences", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelStructureReferences(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        cir.setReturnValue(chunking$dispatchWorkerStage(chunk.getPos(), 2, null,
                () -> ChunkStatusTasks.generateStructureReferences(context, step, cache, chunk)));
    }

    @Inject(method = "generateBiomes", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelBiomes(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        cir.setReturnValue(chunking$dispatchWorkerStage(chunk.getPos(), 3, StageTimer.Stage.BIOMES,
                () -> ChunkStatusTasks.generateBiomes(context, step, cache, chunk)));
    }

    @Inject(method = "generateNoise", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelNoise(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        cir.setReturnValue(chunking$dispatchWorkerStage(chunk.getPos(), 4, StageTimer.Stage.NOISE,
                () -> ChunkStatusTasks.generateNoise(context, step, cache, chunk)));
    }

    @Inject(method = "generateSurface", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelSurface(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        cir.setReturnValue(chunking$dispatchWorkerStage(chunk.getPos(), 5, StageTimer.Stage.SURFACE,
                () -> ChunkStatusTasks.generateSurface(context, step, cache, chunk)));
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelCarvers(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        cir.setReturnValue(chunking$dispatchWorkerStage(chunk.getPos(), 6, StageTimer.Stage.CARVERS,
                () -> ChunkStatusTasks.generateCarvers(context, step, cache, chunk)));
    }

    @Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
    private static void chunking$parallelFeatures(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || CHUNKING$IN_WORKER.get()) {
            return;
        }
        StageTimer profiler = ChunkEngine.get().stageProfiler();
        profiler.enterRealWorldgenTask();
        ChunkPos pos = chunk.getPos();
        Executor pool = ChunkEngine.get().workerPool().executor();
        final CompletableFuture<ChunkAccess> stageFuture;
        try {
            stageFuture = ChunkEngine.get().featureLock().executeAsync(
                    pos.x(), pos.z(), Math.max(1, step.blockStateWriteRadius()), () -> {
                long t0 = System.nanoTime();
                CHUNKING$IN_WORKER.set(Boolean.TRUE);
                try {
                    return ChunkStatusTasks.generateFeatures(context, step, cache, chunk).join();
                } catch (Throwable t) {
                    CHUNKING$LOGGER.error("[Chunking] Worldgen stage FEATURES failed at {}: ", pos, t);
                    throw t;
                } finally {
                    CHUNKING$IN_WORKER.set(Boolean.FALSE);
                    profiler.record(StageTimer.Stage.FEATURES, System.nanoTime() - t0);
                    profiler.exitRealWorldgenTask();
                }
            }, pool);
        } catch (Throwable t) {
            profiler.exitRealWorldgenTask();
            throw t;
        }
        TaskQueue.captureStageFuture(stageFuture);
        cir.setReturnValue(stageFuture);
    }

    @WrapOperation(
            method = "full",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private static CompletableFuture<ChunkAccess> chunking$paceFullPromotion(
            Supplier<ChunkAccess> promotionTask,
            Executor mainThreadExecutor,
            Operation<CompletableFuture<ChunkAccess>> original,
            @Local(argsOnly = true) WorldGenContext context,
            @Local(argsOnly = true) ChunkAccess chunk
    ) {
        ChunkPos pos = chunk.getPos();
        if (!LoadPacer.shouldPace(context.level(), pos.x(), pos.z())) {
            return original.call(promotionTask, mainThreadExecutor);
        }
        return LoadPacer.enqueue(
                context.level(), pos.x(), pos.z(), promotionTask, mainThreadExecutor);
    }
}
