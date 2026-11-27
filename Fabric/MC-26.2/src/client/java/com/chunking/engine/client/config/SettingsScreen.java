package com.chunking.engine.client.config;

import com.chunking.engine.ChunkingMod;
import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.client.DebugHud;
import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.mixin.MixinPlugin;
import com.chunking.engine.scheduler.FrameGuard;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Entry point for Chunking's settings screen. Uses Cloth Config when installed and falls back
 * cleanly to a built-in vanilla screen when Cloth Config is absent.
 */
public final class SettingsScreen extends Screen {
    private static final int[] RENDER_DISTANCE_STEPS = {32, 40, 48, 56, 64};

    private final Screen parent;
    private final int initialWorkers;
    private final int initialRenderDistance;
    private int renderDistance;
    private int configuredWorkers;
    private boolean smoothMode;
    private boolean fastSaving;
    private boolean scaleFog;
    private boolean liveHudOnLaunch;

    private SettingsScreen(Screen parent) {
        super(Component.literal("Chunking Settings"));
        this.parent = parent;
        this.renderDistance = Math.max(32, Math.min(64, ModConfig.FULL_CHUNK_RADIUS));
        this.configuredWorkers = Math.max(0, Math.min(maxWorkerChoices(), ModConfig.CONFIGURED_WORLDGEN_WORKERS));
        this.initialWorkers = this.configuredWorkers;
        this.initialRenderDistance = this.renderDistance;
        this.smoothMode = ModConfig.ADAPTIVE_GOVERNOR;
        this.fastSaving = ModConfig.FAST_ASYNC_SAVING;
        this.scaleFog = ModConfig.SCALE_DIMENSION_FOG;
        this.liveHudOnLaunch = ModConfig.LIVE_BENCHMARK_HUD;
    }

    public static Screen create(Screen parent) {
        if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
            try {
                return ClothSettingsScreen.create(parent);
            } catch (LinkageError | RuntimeException e) {
                ChunkingMod.LOGGER.warn("[Chunking] Cloth Config screen failed; using built-in settings screen", e);
            }
        }
        return new SettingsScreen(parent);
    }

    private static int maxWorkerChoices() {
        return Math.max(4, Math.min(64, Runtime.getRuntime().availableProcessors()));
    }

    private static int nextRenderDistance(int current) {
        for (int step : RENDER_DISTANCE_STEPS) {
            if (step > current) {
                return step;
            }
        }
        return RENDER_DISTANCE_STEPS[0];
    }

    private static int nextWorkers(int current, int max) {
        if (current == 0) {
            return 1;
        }
        if (current < 8) {
            return Math.min(max, current + 1);
        }
        if (current < max) {
            return Math.min(max, current + 4);
        }
        return 0;
    }

    private static Component workersLabel(int configured) {
        if (configured == 0) {
            return Component.literal("Threads: Auto (" + ModConfig.defaultWorldgenWorkers() + ")");
        }
        return Component.literal("Threads: " + configured);
    }

    private static Component toggleLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    private boolean needsRestart() {
        return this.configuredWorkers != this.initialWorkers
                || (MixinPlugin.isSodiumLoaded() && this.renderDistance != this.initialRenderDistance);
    }

    @Override
    protected void init() {
        int colW = 156;
        int leftX = this.width / 2 - colW - 4;
        int rightX = this.width / 2 + 4;
        int topY = Math.max(36, this.height / 6);
        int rowH = 24;
        int maxWorkers = maxWorkerChoices();

        addRenderableWidget(Button.builder(workersLabel(this.configuredWorkers), btn -> {
            this.configuredWorkers = nextWorkers(this.configuredWorkers, maxWorkers);
            btn.setMessage(workersLabel(this.configuredWorkers));
        }).bounds(leftX, topY, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Smooth Gameplay", this.smoothMode), btn -> {
            this.smoothMode = !this.smoothMode;
            btn.setMessage(toggleLabel("Smooth Gameplay", this.smoothMode));
        }).bounds(rightX, topY, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Fast Chunk Saving", this.fastSaving), btn -> {
            this.fastSaving = !this.fastSaving;
            btn.setMessage(toggleLabel("Fast Chunk Saving", this.fastSaving));
        }).bounds(leftX, topY + rowH, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Max Render Distance: " + this.renderDistance), btn -> {
            this.renderDistance = nextRenderDistance(this.renderDistance);
            btn.setMessage(Component.literal("Max Render Distance: " + this.renderDistance));
        }).bounds(rightX, topY + rowH, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Better Nether/End Fog", this.scaleFog), btn -> {
            this.scaleFog = !this.scaleFog;
            btn.setMessage(toggleLabel("Better Nether/End Fog", this.scaleFog));
        }).bounds(leftX, topY + rowH * 2, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("HUD on Launch", this.liveHudOnLaunch), btn -> {
            this.liveHudOnLaunch = !this.liveHudOnLaunch;
            btn.setMessage(toggleLabel("HUD on Launch", this.liveHudOnLaunch));
        }).bounds(rightX, topY + rowH * 2, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reset Defaults"), btn -> {
            this.configuredWorkers = 0;
            this.smoothMode = true;
            this.fastSaving = true;
            this.renderDistance = 64;
            this.scaleFog = true;
            this.liveHudOnLaunch = false;
            rebuildWidgets();
        }).bounds(this.width / 2 - 75, topY + rowH * 3 + 4, 150, 20).build());

        int bottomY = Math.min(this.height - 28, topY + rowH * 4 + 16);
        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> {
            saveAndApply();
            onClose();
        }).bounds(this.width / 2 - 100, bottomY, 200, 20).build());
    }

    private void saveAndApply() {
        ModConfig.FULL_CHUNK_RADIUS = this.renderDistance;
        ModConfig.ADAPTIVE_GOVERNOR = this.smoothMode;
        ModConfig.FAST_ASYNC_SAVING = this.fastSaving;
        ModConfig.SCALE_DIMENSION_FOG = this.scaleFog;
        ModConfig.LIVE_BENCHMARK_HUD = this.liveHudOnLaunch;
        ModConfig.setConfiguredWorkers(this.configuredWorkers);

        DebugHud.setVisible(this.liveHudOnLaunch);
        ChunkEngine.get().workerPool().setParallelism(ModConfig.WORLDGEN_WORKERS);
        if (this.smoothMode) {
            FrameGuard.get().start(ChunkEngine.get().workerPool().parallelism(), ChunkingMod.LOGGER::info);
        } else {
            FrameGuard.get().stop();
        }
        ModConfig.save();
        ClientRuntime.refreshRenderDistanceSlider();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        if (needsRestart()) {
            int msgY = Math.min(this.height - 44, Math.max(36, this.height / 6) + 24 * 4 + 2);
            graphics.centeredText(
                    this.font,
                    Component.literal("Restart Minecraft to apply thread/render changes cleanly."),
                    this.width / 2,
                    msgY,
                    0xFFFACC15
            );
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
