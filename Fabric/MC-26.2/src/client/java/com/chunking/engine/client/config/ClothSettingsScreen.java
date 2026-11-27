package com.chunking.engine.client.config;

import com.chunking.engine.ChunkingMod;
import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.scheduler.FrameGuard;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cloth Config GUI kept in its own class so {@link SettingsScreen} never loads Cloth Config
 * bytecode when the optional dependency is absent.
 */
final class ClothSettingsScreen {
    private ClothSettingsScreen() {}

    static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Chunking Settings"));
        builder.setSavingRunnable(() -> {
            ModConfig.save();
            ClientRuntime.refreshRenderDistanceSlider();
        });

        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory visuals = builder.getOrCreateCategory(Component.literal("Render Distance & Visuals"));

        int clampedRenderDistance = Math.max(8, Math.min(64, ModConfig.FULL_CHUNK_RADIUS));
        visuals.addEntry(entries.startIntSlider(Component.literal("Max Render Distance"), clampedRenderDistance, 8, 64)
                .setDefaultValue(64)
                .setTooltip(Component.literal("Maximum real chunk render distance allowed on the video slider (up to 64 chunks)."))
                .setSaveConsumer(val -> ModConfig.FULL_CHUNK_RADIUS = val)
                .build());

        visuals.addEntry(entries.startBooleanToggle(Component.literal("Scale Fog"), ModConfig.SCALE_DIMENSION_FOG)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Scales Nether and End fog distance to match your Render Distance."))
                .setSaveConsumer(val -> ModConfig.SCALE_DIMENSION_FOG = val)
                .build());

        visuals.addEntry(entries.startBooleanToggle(Component.literal("Reduce Blur"), ModConfig.REDUCE_SCREEN_BLUR)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Disables in-game menu background blur passes that can cause frame hitches."))
                .setSaveConsumer(val -> ModConfig.REDUCE_SCREEN_BLUR = val)
                .build());

        ConfigCategory worldgen = builder.getOrCreateCategory(Component.literal("Worldgen & Performance"));

        int maxSliderWorkers = Math.max(32, Math.min(256, Runtime.getRuntime().availableProcessors()));
        int clampedWorkers = Math.max(1, Math.min(maxSliderWorkers, ModConfig.WORLDGEN_WORKERS));
        worldgen.addEntry(entries.startIntSlider(Component.literal("Workers"), clampedWorkers, 1, maxSliderWorkers)
                .setDefaultValue(ModConfig.defaultWorldgenWorkers())
                .setTooltip(Component.literal("Parallel worldgen worker threads (applied live on save)."))
                .setSaveConsumer(val -> {
                    ModConfig.WORLDGEN_WORKERS = val;
                    ChunkEngine.get().workerPool().setParallelism(val);
                })
                .build());

        worldgen.addEntry(entries.startBooleanToggle(Component.literal("Live Debug HUD"), ModConfig.LIVE_BENCHMARK_HUD)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Shows live FPS, 1% lows, MSPT, chunk speed, and stage timings (rebindable in Controls)."))
                .setSaveConsumer(val -> ModConfig.LIVE_BENCHMARK_HUD = val)
                .build());

        worldgen.addEntry(entries.startBooleanToggle(Component.literal("Smooth Frame Guard"), ModConfig.ADAPTIVE_GOVERNOR)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Watches frame time, tick time, and GC pressure to pace background chunk work before hitches occur."))
                .setSaveConsumer(val -> {
                    ModConfig.ADAPTIVE_GOVERNOR = val;
                    if (val) {
                        FrameGuard.get().start(ChunkEngine.get().workerPool().parallelism(), ChunkingMod.LOGGER::info);
                    } else {
                        FrameGuard.get().stop();
                    }
                })
                .build());

        worldgen.addEntry(entries.startBooleanToggle(Component.literal("Parallel Worldgen"), ModConfig.PARALLEL_STATUS_TASKS)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Runs worldgen stages across all worker threads."))
                .setSaveConsumer(val -> ModConfig.PARALLEL_STATUS_TASKS = val)
                .build());

        worldgen.addEntry(entries.startBooleanToggle(Component.literal("Fast Math & Noise"), ModConfig.SIMD_NOISE)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Uses fast noise and terrain evaluation kernels."))
                .setSaveConsumer(val -> ModConfig.SIMD_NOISE = val)
                .build());

        worldgen.addEntry(entries.startBooleanToggle(Component.literal("Load Ahead of Camera"), ModConfig.VELOCITY_CONE_SCHEDULER)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Prioritizes chunks ahead of your movement and look direction."))
                .setSaveConsumer(val -> ModConfig.VELOCITY_CONE_SCHEDULER = val)
                .build());

        return builder.build();
    }
}
