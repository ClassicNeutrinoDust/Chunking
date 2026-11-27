package com.chunking.engine.client.config;

import com.chunking.engine.ChunkingMod;
import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
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
    private static final int[] RENDER_DISTANCE_STEPS = {8, 12, 16, 24, 32, 48, 64};

    private final Screen parent;
    private int renderDistance;
    private int workers;
    private boolean liveHud;
    private boolean frameGuard;
    private boolean parallelWorldgen;
    private boolean fastNoise;
    private boolean loadAhead;
    private boolean scaleFog;
    private boolean reduceBlur;

    private SettingsScreen(Screen parent) {
        super(Component.literal("Chunking Settings"));
        this.parent = parent;
        this.renderDistance = Math.max(8, Math.min(64, ModConfig.FULL_CHUNK_RADIUS));
        this.workers = Math.max(1, Math.min(maxWorkerChoices(), ModConfig.WORLDGEN_WORKERS));
        this.liveHud = ModConfig.LIVE_BENCHMARK_HUD;
        this.frameGuard = ModConfig.ADAPTIVE_GOVERNOR;
        this.parallelWorldgen = ModConfig.PARALLEL_STATUS_TASKS;
        this.fastNoise = ModConfig.SIMD_NOISE;
        this.loadAhead = ModConfig.VELOCITY_CONE_SCHEDULER;
        this.scaleFog = ModConfig.SCALE_DIMENSION_FOG;
        this.reduceBlur = ModConfig.REDUCE_SCREEN_BLUR;
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
        return Math.max(8, Math.min(256, Runtime.getRuntime().availableProcessors()));
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
        if (current < 8) {
            return Math.min(max, current + 1);
        }
        if (current < max) {
            return Math.min(max, current + 4);
        }
        return 1;
    }

    private static Component toggleLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    protected void init() {
        int colW = 152;
        int leftX = this.width / 2 - colW - 4;
        int rightX = this.width / 2 + 4;
        int topY = Math.max(32, this.height / 6);
        int rowH = 24;
        int maxWorkers = maxWorkerChoices();

        addRenderableWidget(Button.builder(Component.literal("Max Render Distance: " + this.renderDistance), btn -> {
            this.renderDistance = nextRenderDistance(this.renderDistance);
            btn.setMessage(Component.literal("Max Render Distance: " + this.renderDistance));
        }).bounds(leftX, topY, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Workers: " + this.workers), btn -> {
            this.workers = nextWorkers(this.workers, maxWorkers);
            btn.setMessage(Component.literal("Workers: " + this.workers));
        }).bounds(rightX, topY, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Live Debug HUD", this.liveHud), btn -> {
            this.liveHud = !this.liveHud;
            btn.setMessage(toggleLabel("Live Debug HUD", this.liveHud));
        }).bounds(leftX, topY + rowH, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Smooth Frame Guard", this.frameGuard), btn -> {
            this.frameGuard = !this.frameGuard;
            btn.setMessage(toggleLabel("Smooth Frame Guard", this.frameGuard));
        }).bounds(rightX, topY + rowH, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Parallel Worldgen", this.parallelWorldgen), btn -> {
            this.parallelWorldgen = !this.parallelWorldgen;
            btn.setMessage(toggleLabel("Parallel Worldgen", this.parallelWorldgen));
        }).bounds(leftX, topY + rowH * 2, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Fast Math & Noise", this.fastNoise), btn -> {
            this.fastNoise = !this.fastNoise;
            btn.setMessage(toggleLabel("Fast Math & Noise", this.fastNoise));
        }).bounds(rightX, topY + rowH * 2, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Load Ahead of Camera", this.loadAhead), btn -> {
            this.loadAhead = !this.loadAhead;
            btn.setMessage(toggleLabel("Load Ahead of Camera", this.loadAhead));
        }).bounds(leftX, topY + rowH * 3, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Scale Fog", this.scaleFog), btn -> {
            this.scaleFog = !this.scaleFog;
            btn.setMessage(toggleLabel("Scale Fog", this.scaleFog));
        }).bounds(rightX, topY + rowH * 3, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Reduce Blur", this.reduceBlur), btn -> {
            this.reduceBlur = !this.reduceBlur;
            btn.setMessage(toggleLabel("Reduce Blur", this.reduceBlur));
        }).bounds(leftX, topY + rowH * 4, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reset Defaults"), btn -> {
            this.renderDistance = 64;
            this.workers = Math.max(1, Math.min(maxWorkers, ModConfig.defaultWorldgenWorkers()));
            this.liveHud = false;
            this.frameGuard = true;
            this.parallelWorldgen = true;
            this.fastNoise = true;
            this.loadAhead = true;
            this.scaleFog = true;
            this.reduceBlur = true;
            rebuildWidgets();
        }).bounds(rightX, topY + rowH * 4, colW, 20).build());

        int bottomY = Math.min(this.height - 28, topY + rowH * 5 + 10);
        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> {
            saveAndApply();
            onClose();
        }).bounds(this.width / 2 - 100, bottomY, 200, 20).build());
    }

    private void saveAndApply() {
        ModConfig.FULL_CHUNK_RADIUS = this.renderDistance;
        ModConfig.LIVE_BENCHMARK_HUD = this.liveHud;
        ModConfig.ADAPTIVE_GOVERNOR = this.frameGuard;
        ModConfig.PARALLEL_STATUS_TASKS = this.parallelWorldgen;
        ModConfig.SIMD_NOISE = this.fastNoise;
        ModConfig.VELOCITY_CONE_SCHEDULER = this.loadAhead;
        ModConfig.SCALE_DIMENSION_FOG = this.scaleFog;
        ModConfig.REDUCE_SCREEN_BLUR = this.reduceBlur;
        ModConfig.WORLDGEN_WORKERS = this.workers;

        ChunkEngine.get().workerPool().setParallelism(this.workers);
        if (this.frameGuard) {
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
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
