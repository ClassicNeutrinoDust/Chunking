package com.chunking.engine.config;

import com.chunking.engine.ChunkingMod;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.mixin.MixinPlugin;
import com.chunking.engine.scheduler.FrameGuard;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime settings for Chunking. System properties (-Dchunking.*) override config/chunking.properties.
 */
public final class ModConfig {
    private static final Path CONFIG_PATH = resolveConfigPath();
    private static volatile Properties FILE = loadFile();

    public static volatile int FULL_CHUNK_RADIUS;
    public static volatile boolean PARALLEL_STATUS_TASKS;
    public static volatile boolean SIMD_NOISE;
    public static volatile boolean FAST_SURFACE_RULES;
    public static volatile boolean VELOCITY_CONE_SCHEDULER;
    public static volatile boolean ADAPTIVE_GOVERNOR;
    public static volatile boolean FAST_ASYNC_SAVING;
    public static volatile boolean LIVE_BENCHMARK_HUD;
    public static volatile boolean SCALE_DIMENSION_FOG;
    public static volatile boolean REDUCE_SCREEN_BLUR;
    public static volatile int CONFIGURED_WORLDGEN_WORKERS;
    public static volatile int WORLDGEN_WORKERS;
    public static final int MAX_CONCURRENT_DISPATCHES;

    static {
        applyProperties();
        MAX_CONCURRENT_DISPATCHES = integer(
                "chunking.max_concurrent_dispatches",
                "max_concurrent_dispatches",
                Math.max(4, Math.min(2048, WORLDGEN_WORKERS * 4)),
                2,
                2048
        );
        if (!Files.isRegularFile(CONFIG_PATH)) {
            save();
        }
    }

    private ModConfig() {}

    private static void applyProperties() {
        FULL_CHUNK_RADIUS = integer("chunking.full_chunk_radius", "full_chunk_radius", 64, 32, 64);
        PARALLEL_STATUS_TASKS = bool("chunking.parallel_status_tasks", "parallel_status_tasks", true);
        SIMD_NOISE = bool("chunking.simd_noise", "simd_noise", true);
        FAST_SURFACE_RULES = bool("chunking.fast_surface_rules", "fast_surface_rules", true);
        VELOCITY_CONE_SCHEDULER = bool("chunking.velocity_cone_scheduler", "velocity_cone_scheduler", true);
        ADAPTIVE_GOVERNOR = bool("chunking.adaptive_governor", "adaptive_governor", true);
        FAST_ASYNC_SAVING = bool("chunking.fast_async_saving", "fast_async_saving", true);
        LIVE_BENCHMARK_HUD = bool("chunking.live_benchmark_hud", "live_benchmark_hud", false);
        SCALE_DIMENSION_FOG = bool("chunking.scale_dimension_fog", "scale_dimension_fog", true);
        REDUCE_SCREEN_BLUR = bool("chunking.reduce_screen_blur", "reduce_screen_blur", false);

        int rawWorkers = integer("chunking.worldgen_workers", "worldgen_workers", 0, 0, 256);
        CONFIGURED_WORLDGEN_WORKERS = rawWorkers;
        WORLDGEN_WORKERS = rawWorkers == 0 ? defaultWorldgenWorkers() : rawWorkers;
    }

    public static void setConfiguredWorkers(int configured) {
        int clamped = Math.max(0, Math.min(256, configured));
        CONFIGURED_WORLDGEN_WORKERS = clamped;
        WORLDGEN_WORKERS = clamped == 0 ? defaultWorldgenWorkers() : clamped;
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("chunking.properties");
        } catch (Throwable ignored) {
            return Path.of("config", "chunking.properties");
        }
    }

    public static boolean isClientEnvironment() {
        try {
            return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int defaultWorldgenWorkers() {
        int cpus = Runtime.getRuntime().availableProcessors();
        int byCpu;
        if (cpus <= 2) {
            byCpu = 1;
        } else if (cpus <= 8) {
            byCpu = cpus - Math.max(1, cpus / 4);
        } else {
            int reserved = cpus <= 24 ? 4 : Math.max(4, Math.min(12, cpus / 10));
            byCpu = Math.min(256, Math.max(8, cpus - reserved));
        }
        // Keep a ~1 GB reserve on small heaps so low-RAM clients do not OOM.
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        int byHeap = maxHeapMb <= 2560L
                ? (int) Math.max(1L, Math.min(3L, Math.max(0L, maxHeapMb - 1024L) / 350L))
                : (int) Math.max(1L, Math.min(256L, maxHeapMb / 220L));
        int result = Math.max(1, Math.min(byCpu, byHeap));
        if (isClientEnvironment()) {
            int byClientCpu = maxHeapMb <= 4608L
                    ? Math.max(2, Math.min(6, cpus / 3))
                    : Math.max(2, Math.min(8, Math.max(2, cpus / 2 - 2)));
            int clientWorkers = Math.min(result, byClientCpu);
            return MixinPlugin.isLodModLoaded() ? Math.max(1, clientWorkers - 2) : clientWorkers;
        }
        return result;
    }

    public static int effectiveMaxConcurrentDispatches() {
        int w = Math.max(1, WORLDGEN_WORKERS);
        return Math.max(4, Math.min(2048, w * 4));
    }

    public static int effectiveFullChunkRadius(int requestedRenderDistance) {
        return Math.max(2, Math.min(FULL_CHUNK_RADIUS, requestedRenderDistance));
    }

    public static int maxSliderRenderDistance() {
        return Math.max(32, FULL_CHUNK_RADIUS);
    }

    public static int maxPlayerTicketDistance() {
        return Math.max(64, Math.min(124, FULL_CHUNK_RADIUS));
    }

    public static synchronized void reload() {
        FILE = loadFile();
        applyProperties();
        ChunkEngine.get().workerPool().setParallelism(WORLDGEN_WORKERS);
        if (ADAPTIVE_GOVERNOR) {
            FrameGuard.get().start(WORLDGEN_WORKERS, ChunkingMod.LOGGER::info);
        } else {
            FrameGuard.get().stop();
        }
    }

    public static synchronized void save() {
        String text = ""
                + "# ===================================================================\n"
                + "# Chunking Engine Configuration\n"
                + "# Most changes apply immediately, or on restart if noted.\n"
                + "# ===================================================================\n\n"
                + "# --- Performance ---\n\n"
                + "# Number of world generation threads (0 = Auto, 1-256 = manual).\n"
                + "# Auto picks a safe count for your CPU and RAM. Restart recommended after changing.\n"
                + "worldgen_workers=" + CONFIGURED_WORLDGEN_WORKERS + "\n\n"
                + "# Smooth Gameplay Mode: paces background chunk loading so FPS and TPS stay smooth\n"
                + "# while flying or exploring. Automatically bypassed during Chunky pregeneration.\n"
                + "adaptive_governor=" + ADAPTIVE_GOVERNOR + "\n\n"
                + "# Fast Chunk Saving: writes chunk data asynchronously in the background for faster\n"
                + "# saving. Turn off only if you want strict per-write disk flushes.\n"
                + "fast_async_saving=" + FAST_ASYNC_SAVING + "\n\n"
                + "# --- Visuals & HUD ---\n\n"
                + "# Maximum Render Distance allowed on the Video Settings slider (32 to 64 chunks).\n"
                + "# Note: If Sodium is installed, restart the game after changing this.\n"
                + "full_chunk_radius=" + FULL_CHUNK_RADIUS + "\n\n"
                + "# Better Nether & End Fog: extends Nether and End fog to match your Render Distance.\n"
                + "# Automatically yields if Voxy or Distant Horizons is installed.\n"
                + "scale_dimension_fog=" + SCALE_DIMENSION_FOG + "\n\n"
                + "# Show Performance HUD on Launch: displays live FPS, MSPT, and chunk speed on startup.\n"
                + "# Press F7 in-game anytime to temporarily show or hide the HUD.\n"
                + "live_benchmark_hud=" + LIVE_BENCHMARK_HUD + "\n";

        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.err.println("[Chunking] Could not write " + CONFIG_PATH.toAbsolutePath() + ": " + e);
        }
    }

    private static Properties loadFile() {
        Properties props = new Properties();
        if (!Files.isRegularFile(CONFIG_PATH)) return props;
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
    }

    private static boolean bool(String sysKey, String fileKey, boolean fallback) {
        String val = System.getProperty(sysKey);
        if (val == null) val = FILE.getProperty(fileKey);
        return val == null ? fallback : Boolean.parseBoolean(val.trim());
    }

    private static int integer(String sysKey, String fileKey, int fallback, int min, int max) {
        String val = System.getProperty(sysKey);
        if (val == null) val = FILE.getProperty(fileKey);
        if (val == null) return fallback;
        String trimmed = val.trim();
        if ("auto".equalsIgnoreCase(trimmed)) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
