package com.chunking.engine;

import com.chunking.engine.command.ChunkingCommand;
import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint for the Chunking mod.
 */
public final class ChunkingMod implements ModInitializer {
    public static final String MOD_ID = "chunking";
    public static final Logger LOGGER = LoggerFactory.getLogger("Chunking");
    private static volatile long serverTickStartNs;

    private static void resetSessionState() {
        serverTickStartNs = 0L;
        ChunkEngine.get().velocityTracker().clear();
        com.chunking.engine.scheduler.FrameGuard.get().resetSession();
        com.chunking.engine.scheduler.TaskQueue.resetSession();
        com.chunking.engine.scheduler.LoadPacer.resetSession();
        com.chunking.engine.scheduler.TickBudget.resetSession();
        com.chunking.engine.core.LiveStats.get().resetSession();
        com.chunking.engine.world.ModCompat.resetSession();
        com.chunking.engine.world.BlockTagBits.invalidateTagCache();
        com.chunking.engine.io.EncodedCache.clear();
        com.chunking.engine.io.SectionWriter.clearBiomeCache();
        com.chunking.engine.io.SectionReader.clearBiomeCache();
    }

    @Override
    public void onInitialize() {
        if (Boolean.getBoolean("chunking.passive")) {
            LOGGER.info("Chunking PASSIVE mode: all mixins disabled");
            return;
        }
        ChunkEngine engine = ChunkEngine.get();
        if (ModConfig.ADAPTIVE_GOVERNOR) {
            com.chunking.engine.scheduler.FrameGuard.get().start(engine.workerPool().parallelism(), LOGGER::info);
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ChunkingCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> resetSessionState());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                com.chunking.engine.world.BlockTagBits.invalidateTagCache();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            resetSessionState();
            if (!ModConfig.isClientEnvironment()) {
                com.chunking.engine.scheduler.FrameGuard.get().stop();
                ChunkEngine.get().close();
            }
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> serverTickStartNs = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long start = serverTickStartNs;
            if (start == 0L) {
                return;
            }
            double fullMs = (System.nanoTime() - start) / 1_000_000.0D;
            int tick = server.getTickCount();
            com.chunking.engine.scheduler.TickBudget.recordFullTickMs(tick, fullMs);
            if (ModConfig.ADAPTIVE_GOVERNOR
                    && com.chunking.engine.scheduler.TickBudget.claimGovernorTick(tick)) {
                com.chunking.engine.scheduler.FrameGuard.get().onServerTick(
                        Math.max(fullMs, com.chunking.engine.scheduler.TickBudget.measureEffectiveTickMs(server)));
            }
        });
        LOGGER.info(
                "Chunking 1.0.0 initialized: workers={}, fullChunkRadius={}, simd={}",
                engine.workerPool().parallelism(),
                ModConfig.FULL_CHUNK_RADIUS,
                ModConfig.SIMD_NOISE
        );
    }
}
