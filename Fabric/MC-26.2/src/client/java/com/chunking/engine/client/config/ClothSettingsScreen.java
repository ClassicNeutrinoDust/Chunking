package com.chunking.engine.client.config;

import com.chunking.engine.ChunkingMod;
import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.client.DebugHud;
import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.mixin.MixinPlugin;
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

        ConfigCategory performance = builder.getOrCreateCategory(Component.literal("Performance"));

        int maxSliderWorkers = Math.max(4, Math.min(64, Runtime.getRuntime().availableProcessors()));
        int currentWorkers = Math.max(0, Math.min(maxSliderWorkers, ModConfig.CONFIGURED_WORLDGEN_WORKERS));
        performance.addEntry(entries.startIntSlider(Component.literal("Worldgen Threads"), currentWorkers, 0, maxSliderWorkers)
                .setDefaultValue(0)
                .setTextGetter(val -> val == 0
                        ? Component.literal("Auto (" + ModConfig.defaultWorldgenWorkers() + ")")
                        : Component.literal(val + (val == 1 ? " thread" : " threads")))
                .setTooltip(Component.literal("CPU threads used for world generation. Auto picks a safe count for your PC. Restart recommended after changing."))
                .requireRestart()
                .setSaveConsumer(val -> {
                    ModConfig.setConfiguredWorkers(val);
                    ChunkEngine.get().workerPool().setParallelism(ModConfig.WORLDGEN_WORKERS);
                })
                .build());

        performance.addEntry(entries.startBooleanToggle(Component.literal("Smooth Gameplay Mode"), ModConfig.ADAPTIVE_GOVERNOR)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Paces background chunk loading so FPS and TPS stay smooth while flying or exploring. Automatically bypassed during Chunky pregeneration."))
                .setSaveConsumer(val -> {
                    ModConfig.ADAPTIVE_GOVERNOR = val;
                    if (val) {
                        FrameGuard.get().start(ChunkEngine.get().workerPool().parallelism(), ChunkingMod.LOGGER::info);
                    } else {
                        FrameGuard.get().stop();
                    }
                })
                .build());

        performance.addEntry(entries.startBooleanToggle(Component.literal("Fast Chunk Saving"), ModConfig.FAST_ASYNC_SAVING)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Saves chunks asynchronously in the background. Turn off for stricter crash-safe disk flushes."))
                .setSaveConsumer(val -> ModConfig.FAST_ASYNC_SAVING = val)
                .build());

        ConfigCategory visuals = builder.getOrCreateCategory(Component.literal("Visuals & HUD"));

        int clampedRenderDistance = Math.max(32, Math.min(64, ModConfig.FULL_CHUNK_RADIUS));
        var rdEntry = entries.startIntSlider(Component.literal("Max Render Distance"), clampedRenderDistance, 32, 64)
                .setDefaultValue(64)
                .setTooltip(Component.literal("Maximum Render Distance allowed on the Video Settings slider (32 to 64 chunks)."))
                .setSaveConsumer(val -> ModConfig.FULL_CHUNK_RADIUS = val);
        if (MixinPlugin.isSodiumLoaded()) {
            rdEntry.requireRestart();
        }
        visuals.addEntry(rdEntry.build());

        visuals.addEntry(entries.startBooleanToggle(Component.literal("Better Nether & End Fog"), ModConfig.SCALE_DIMENSION_FOG)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Extends Nether and End fog to match your Render Distance. Automatically yields if Voxy or Distant Horizons is installed."))
                .setSaveConsumer(val -> ModConfig.SCALE_DIMENSION_FOG = val)
                .build());

        visuals.addEntry(entries.startBooleanToggle(Component.literal("Show Performance HUD on Launch"), ModConfig.LIVE_BENCHMARK_HUD)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Shows FPS, MSPT, and chunk speed automatically on startup. Press F7 in-game anytime to toggle it temporarily."))
                .setSaveConsumer(val -> {
                    ModConfig.LIVE_BENCHMARK_HUD = val;
                    DebugHud.setVisible(val);
                })
                .build());

        return builder.build();
    }
}
