package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link BlockLightEngine#runLightUpdates()} and {@link SkyLightEngine#runLightUpdates()}
 * simultaneously when both queues have pending work on a server {@link ThreadedLevelLightEngine},
 * doubling server lighting throughput on multi-core CPUs across multiple active dimensions.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Shadow
    @Final
    private LightEngine<?, ?> blockEngine;

    @Shadow
    @Final
    private LightEngine<?, ?> skyEngine;

    @Unique
    private static final AtomicInteger CHUNKING$BLOCK_LIGHT_THREAD_IDS = new AtomicInteger(1);

    @Unique
    private static final ExecutorService CHUNKING$BLOCK_LIGHT_EXEC = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() >= 6 ? 3 : 2,
            r -> {
                Thread t = new Thread(r, "Chunking-BlockLight-" + CHUNKING$BLOCK_LIGHT_THREAD_IDS.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
                return t;
            }
    );

    @Inject(method = "runLightUpdates", at = @At("HEAD"), cancellable = true)
    private void chunking$runBlockAndSkyInParallel(CallbackInfoReturnable<Integer> cir) {
        if (!ModConfig.PARALLEL_STATUS_TASKS
                || !((Object) this instanceof ThreadedLevelLightEngine)
                || this.blockEngine == null
                || this.skyEngine == null) {
            return;
        }
        if (this.blockEngine.hasLightWork() && this.skyEngine.hasLightWork()) {
            CompletableFuture<Integer> blockFuture =
                    CompletableFuture.supplyAsync(this.blockEngine::runLightUpdates, CHUNKING$BLOCK_LIGHT_EXEC);
            int skyUpdates;
            Throwable skyFailure = null;
            try {
                skyUpdates = this.skyEngine.runLightUpdates();
            } catch (Throwable t) {
                skyFailure = t;
                throw t;
            } finally {
                if (skyFailure != null) {
                    try {
                        blockFuture.join();
                    } catch (Throwable suppressed) {
                        skyFailure.addSuppressed(suppressed);
                    }
                }
            }
            cir.setReturnValue(skyUpdates + blockFuture.join());
        }
    }
}
