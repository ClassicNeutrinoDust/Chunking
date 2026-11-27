package com.chunking.engine.mixin;

import com.chunking.engine.access.ChunkHolderLightAccess;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * Sends chunks on FULL status and coalesces per-section light updates into primitive bitmasks.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin implements ChunkHolderLightAccess {
    @Shadow
    @Final
    private LevelLightEngine lightEngine;

    @Shadow
    public abstract CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture();

    @Shadow
    public abstract CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture();

    @Shadow
    public abstract CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture();

    @Shadow
    public abstract boolean sectionLightChanged(LightLayer layer, int sectionY);

    @Unique
    private volatile long chunking$dirtySkyLightMask;

    @Unique
    private volatile long chunking$dirtyBlockLightMask;

    @Unique
    private volatile boolean chunking$scheduledLightUndirty;

    @Unique
    private IntOpenHashSet chunking$overflowSkySections;

    @Unique
    private IntOpenHashSet chunking$overflowBlockSections;

    @Redirect(
            method = {"getChunkToSend", "blockChanged", "sectionLightChanged"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"
            ),
            require = 0
    )
    private LevelChunk chunking$sendFullChunkImmediately(ChunkHolder chunkHolder) {
        return this.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    @Override
    public void chunking$queueLightSectionDirty(LightLayer type, int sectionY) {
        int minSection = this.lightEngine.getMinLightSection();
        int bitIdx = sectionY - minSection;
        if (bitIdx < 0 || bitIdx >= 64) {
            synchronized (this) {
                if (type == LightLayer.SKY) {
                    if (this.chunking$overflowSkySections == null) {
                        this.chunking$overflowSkySections = new IntOpenHashSet(8);
                    }
                    this.chunking$overflowSkySections.add(sectionY);
                } else {
                    if (this.chunking$overflowBlockSections == null) {
                        this.chunking$overflowBlockSections = new IntOpenHashSet(8);
                    }
                    this.chunking$overflowBlockSections.add(sectionY);
                }
            }
            return;
        }
        long bit = 1L << bitIdx;
        if (type == LightLayer.SKY) {
            synchronized (this) {
                this.chunking$dirtySkyLightMask |= bit;
            }
        } else {
            synchronized (this) {
                this.chunking$dirtyBlockLightMask |= bit;
            }
        }
    }

    @Override
    public boolean chunking$shouldScheduleUndirty() {
        if (this.chunking$scheduledLightUndirty) {
            return false;
        }
        synchronized (this) {
            if (this.chunking$scheduledLightUndirty) {
                return false;
            }
            this.chunking$scheduledLightUndirty = true;
            return true;
        }
    }

    @Override
    public boolean chunking$undirtyLight() {
        long sky;
        long block;
        int[] extraSky = null;
        int[] extraBlock = null;
        synchronized (this) {
            sky = this.chunking$dirtySkyLightMask;
            this.chunking$dirtySkyLightMask = 0L;
            block = this.chunking$dirtyBlockLightMask;
            this.chunking$dirtyBlockLightMask = 0L;
            if (this.chunking$overflowSkySections != null && !this.chunking$overflowSkySections.isEmpty()) {
                extraSky = this.chunking$overflowSkySections.toIntArray();
                this.chunking$overflowSkySections.clear();
            }
            if (this.chunking$overflowBlockSections != null && !this.chunking$overflowBlockSections.isEmpty()) {
                extraBlock = this.chunking$overflowBlockSections.toIntArray();
                this.chunking$overflowBlockSections.clear();
            }
            this.chunking$scheduledLightUndirty = false;
        }

        int minSection = this.lightEngine.getMinLightSection();
        boolean changed = false;

        while (sky != 0L) {
            int bitIdx = Long.numberOfTrailingZeros(sky);
            changed |= this.sectionLightChanged(LightLayer.SKY, minSection + bitIdx);
            sky &= (sky - 1L);
        }
        if (extraSky != null) {
            for (int sy : extraSky) {
                changed |= this.sectionLightChanged(LightLayer.SKY, sy);
            }
        }

        while (block != 0L) {
            int bitIdx = Long.numberOfTrailingZeros(block);
            changed |= this.sectionLightChanged(LightLayer.BLOCK, minSection + bitIdx);
            block &= (block - 1L);
        }
        if (extraBlock != null) {
            for (int sy : extraBlock) {
                changed |= this.sectionLightChanged(LightLayer.BLOCK, sy);
            }
        }
        return changed;
    }

    // Skip onFullChunkStatusChange if the chunk was demoted before promotion completed.
    @Dynamic
    @WrapWithCondition(
            method = "lambda$scheduleFullChunkPromotion$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;onFullChunkStatusChange(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V"
            ),
            require = 0
    )
    private boolean chunking$ensureStatusReachedBeforeCallback(
            ChunkMap chunkMap,
            ChunkPos chunkPos,
            FullChunkStatus status
    ) {
        return switch (status) {
            case INACCESSIBLE -> true;
            case FULL -> chunking$isStatusReached(this.getFullChunkFuture());
            case BLOCK_TICKING -> chunking$isStatusReached(this.getTickingChunkFuture());
            case ENTITY_TICKING -> chunking$isStatusReached(this.getEntityTickingChunkFuture());
        };
    }

    @Unique
    private static boolean chunking$isStatusReached(CompletableFuture<ChunkResult<LevelChunk>> future) {
        return future != null
                && future.isDone()
                && !future.isCompletedExceptionally()
                && future.join().isSuccess();
    }
}
