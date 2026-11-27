package com.chunking.engine.command;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.core.StageTimer;
import com.chunking.engine.mixin.MixinPlugin;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.LoadPacer;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.scheduler.WorkerPool;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;

import java.util.Locale;

/**
 * Admin command ({@code /chunking status|workers|reload}) for live inspection and tuning.
 */
public final class ChunkingCommand {
    private ChunkingCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("chunking")
                .requires(ChunkingCommand::hasAccess)
                .then(Commands.literal("status").executes(ctx -> showStatus(ctx.getSource())))
                .then(Commands.literal("workers")
                        .then(Commands.literal("auto").executes(ctx -> setWorkers(ctx.getSource(), ModConfig.defaultWorldgenWorkers(), true)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> setWorkers(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"), false))))
                .then(Commands.literal("reload").executes(ctx -> reloadConfig(ctx.getSource()))));
    }

    private static boolean hasAccess(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || source.getEntity() == null) {
            return true;
        }
        MinecraftServer server = source.getServer();
        net.minecraft.server.level.ServerPlayer player = source.getPlayer();
        return server != null && !server.isDedicatedServer()
                && player != null && server.isSingleplayerOwner(player.nameAndId());
    }

    private static int showStatus(CommandSourceStack source) {
        ChunkEngine engine = ChunkEngine.get();
        WorkerPool pool = engine.workerPool();
        FrameGuard guard = FrameGuard.get();
        StageTimer timer = engine.stageProfiler();

        long queueDepth = pool.queueDepth() + engine.featureLock().pendingCount();
        double avgMspt = TickBudget.smoothedTickMs();
        MinecraftServer server = source.getServer();
        if (server != null) {
            avgMspt = Math.max(avgMspt, server.getAverageTickTimeNanos() / 1_000_000.0D);
        }
        double peakMspt = TickBudget.recentPeakTickMs();

        String compat = formatCompatFlags();
        String line1 = String.format(Locale.ROOT,
                "[Chunking] workers=%d | queue=%d (features=%d, fullPacer=%d) | guard=%s(%s) | MSPT avg=%.1fms peak=%.1fms",
                pool.parallelism(),
                queueDepth,
                engine.featureLock().activeDispatchedCount(),
                LoadPacer.pendingCount(),
                guard.isRunning() ? guard.level().name() : "OFF",
                ModConfig.ADAPTIVE_GOVERNOR ? "on" : "off",
                avgMspt,
                peakMspt);

        String line2 = String.format(Locale.ROOT,
                "[Chunking] compat=[%s] | stages(ms): struct=%.2f(%d) bio=%.2f(%d) noise=%.2f(%d) surf=%.2f(%d) carv=%.2f(%d) feat=%.2f(%d) light=%.2f(%d)",
                compat,
                timer.averageMillis(StageTimer.Stage.STRUCTURE_STARTS), timer.totalOps(StageTimer.Stage.STRUCTURE_STARTS),
                timer.averageMillis(StageTimer.Stage.BIOMES), timer.totalOps(StageTimer.Stage.BIOMES),
                timer.averageMillis(StageTimer.Stage.NOISE), timer.totalOps(StageTimer.Stage.NOISE),
                timer.averageMillis(StageTimer.Stage.SURFACE), timer.totalOps(StageTimer.Stage.SURFACE),
                timer.averageMillis(StageTimer.Stage.CARVERS), timer.totalOps(StageTimer.Stage.CARVERS),
                timer.averageMillis(StageTimer.Stage.FEATURES), timer.totalOps(StageTimer.Stage.FEATURES),
                timer.averageMillis(StageTimer.Stage.LIGHT), timer.totalOps(StageTimer.Stage.LIGHT));

        source.sendSuccess(() -> Component.literal(line1), false);
        source.sendSuccess(() -> Component.literal(line2), false);
        return 1;
    }

    private static int setWorkers(CommandSourceStack source, int count, boolean auto) {
        int clamped = Math.max(1, Math.min(256, count));
        ModConfig.setConfiguredWorkers(auto ? 0 : clamped);
        ChunkEngine.get().workerPool().setParallelism(ModConfig.WORLDGEN_WORKERS);
        ModConfig.save();
        String msg = auto
                ? "[Chunking] Workers reset to auto (" + ModConfig.WORLDGEN_WORKERS + ") and saved."
                : "[Chunking] Workers set to " + clamped + " and saved.";
        source.sendSuccess(() -> Component.literal(msg), true);
        return ModConfig.WORLDGEN_WORKERS;
    }

    private static int reloadConfig(CommandSourceStack source) {
        ModConfig.reload();
        String msg = String.format(Locale.ROOT,
                "[Chunking] Reloaded config: workers=%d, maxRenderDistance=%d, frameGuard=%s, parallel=%s, fastMath=%s",
                ChunkEngine.get().workerPool().parallelism(),
                ModConfig.FULL_CHUNK_RADIUS,
                ModConfig.ADAPTIVE_GOVERNOR,
                ModConfig.PARALLEL_STATUS_TASKS,
                ModConfig.SIMD_NOISE);
        source.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static String formatCompatFlags() {
        StringBuilder sb = new StringBuilder(64);
        if (MixinPlugin.isLithiumLoaded()) appendTag(sb, "lithium");
        if (MixinPlugin.isSodiumLoaded()) appendTag(sb, "sodium");
        if (MixinPlugin.isVulkanModLoaded()) appendTag(sb, "vulkanmod");
        if (MixinPlugin.isNoisiumLoaded()) appendTag(sb, "noisium");
        if (MixinPlugin.isLodModLoaded()) appendTag(sb, "lod");
        try {
            FabricLoader loader = FabricLoader.getInstance();
            if (loader.isModLoaded("terrablender")) appendTag(sb, "terrablender");
            if (loader.isModLoaded("moonrise")) appendTag(sb, "moonrise");
            if (loader.isModLoaded("scalablelux") || loader.isModLoaded("starlight")) appendTag(sb, "light-mod");
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void appendTag(StringBuilder sb, String tag) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(tag);
    }
}
