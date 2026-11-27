package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.core.LiveStats;
import com.chunking.engine.scheduler.FrameGuard;
import com.mojang.datafixers.DataFixer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.Proxy;
import java.util.List;
import java.util.Optional;

/**
 * Streams a bounded burst of ready chunks while singleplayer is paused, backing off on hitches.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin extends MinecraftServer {
    @Unique
    private static final int CHUNKING$MAX_PAUSED_CHUNKS = 32;

    @Unique
    private boolean chunking$wasPausedThisTick;

    @Unique
    private int chunking$pausedChunksSent;

    public IntegratedServerMixin(
            Thread serverThread,
            LevelStorageSource.LevelStorageAccess session,
            PackRepository dataPackManager,
            WorldStem saveLoader,
            Optional<GameRules> gameRules,
            Proxy proxy,
            DataFixer fixerUpper,
            Services apiServices,
            LevelLoadListener chunkLoadProgress,
            boolean propagatesCrashes,
            NotificationManager compositeManagementListener
    ) {
        super(serverThread, session, dataPackManager, saveLoader, gameRules, proxy, fixerUpper,
                apiServices, chunkLoadProgress, propagatesCrashes, compositeManagementListener);
    }

    @Inject(method = "tickServer", at = @At("HEAD"), require = 0)
    private void chunking$beginTickServer(CallbackInfo ci) {
        this.chunking$wasPausedThisTick = false;
    }

    @Inject(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/IntegratedServer;tickPaused()V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void chunking$sendChunksWhilePaused(CallbackInfo ci) {
        this.chunking$wasPausedThisTick = true;
        if (this.chunking$pausedChunksSent >= CHUNKING$MAX_PAUSED_CHUNKS) {
            return;
        }
        FrameGuard gov = FrameGuard.get();
        if (gov.level() == FrameGuard.Level.SEVERE
                || gov.isInFrameHitch()
                || ClientRuntime.isInLocalFrameSpike()
                || ClientRuntime.isInGuiTransition()) {
            return;
        }
        PlayerList playerList = this.getPlayerList();
        if (playerList == null) {
            return;
        }
        LiveStats telemetry = LiveStats.get();
        List<ServerPlayer> players = playerList.getPlayers();
        for (int i = 0, n = players.size(); i < n; i++) {
            if (this.chunking$pausedChunksSent >= CHUNKING$MAX_PAUSED_CHUNKS) {
                break;
            }
            ServerPlayer player = players.get(i);
            if (player == null || player.connection == null || player.connection.chunkSender == null) {
                continue;
            }
            long before = telemetry.totalServerChunksSent();
            player.connection.suspendFlushing();
            try {
                player.connection.chunkSender.sendNextChunks(player);
            } finally {
                player.connection.resumeFlushing();
            }
            long delta = telemetry.totalServerChunksSent() - before;
            this.chunking$pausedChunksSent += delta > 0L ? (int) Math.min(delta, CHUNKING$MAX_PAUSED_CHUNKS) : 1;
        }
    }

    @Inject(method = "tickServer", at = @At("RETURN"), require = 0)
    private void chunking$endTickServer(CallbackInfo ci) {
        if (!this.chunking$wasPausedThisTick && this.chunking$pausedChunksSent != 0) {
            this.chunking$pausedChunksSent = 0;
        }
    }
}
