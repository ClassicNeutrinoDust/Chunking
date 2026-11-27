package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.access.QueuedPacketAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Enforces an adaptive frame-time &amp; chunk-count budget on queued
 * {@link ClientboundLevelChunkWithLightPacket} instances on Minecraft's client render thread,
 * preserving strict FIFO packet ordering so gameplay, unload, light, and batch-finish ACKs
 * never stall while chunk-load bursts no longer destroy 0.1% / 1% low FPS.
 */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorMixin {
    @Shadow
    @Final
    private Thread runningThread;

    @Shadow
    @Final
    private Queue<Object> packetsToBeHandled;

    @Shadow
    private boolean closed;

    @Inject(method = "processQueuedPackets", at = @At("HEAD"), cancellable = true)
    private void chunking$budgetClientChunkPacketsFifo(CallbackInfo ci) {
        if (this.closed || Thread.currentThread() != this.runningThread) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.packetProcessor() != (Object) this) {
            return;
        }
        Object head = this.packetsToBeHandled.peek();
        if (head == null) {
            ci.cancel();
            return;
        }
        if (!(head instanceof QueuedPacketAccess)) {
            return;
        }

        if (mc.level == null) {
            return;
        }
        FrameGuard gov = FrameGuard.get();
        long budgetNs = gov.packetBudgetNs();
        int minChunks = gov.minChunkPacketsPerFrame();
        int maxChunks = gov.maxChunkPacketsPerFrame();
        boolean guiTransition = ClientRuntime.isInGuiTransition();
        boolean localSpike = ClientRuntime.isInLocalFrameSpike();
        boolean frameHitch = gov.isInFrameHitch() || localSpike;
        boolean movingFast = ClientRuntime.get().isMovingFast();
        boolean turningFast = ClientRuntime.get().isTurningFast();
        if (guiTransition) {
            budgetNs = Math.min(budgetNs, 350_000L);
            minChunks = 1;
            maxChunks = Math.min(maxChunks, 2);
        } else if (frameHitch) {
            budgetNs = Math.min(budgetNs, 600_000L);
            maxChunks = Math.min(maxChunks, Math.max(minChunks, 4));
        } else if (!gov.isInitialWorldLoadWindow()) {
            int cpus = Runtime.getRuntime().availableProcessors();
            if (turningFast && !movingFast) {
                budgetNs = Math.min(Math.max(budgetNs, 1_000_000L), 1_200_000L);
                maxChunks = Math.min(maxChunks, cpus <= 4 ? 4 : 8);
            } else if (movingFast) {
                budgetNs = Math.max(budgetNs, cpus <= 4 ? 1_800_000L : (cpus <= 8 ? 2_200_000L : 2_500_000L));
                maxChunks = Math.max(maxChunks, cpus <= 4 ? 10 : (cpus <= 8 ? 16 : 24));
            } else {
                budgetNs = Math.max(budgetNs, cpus <= 4 ? 1_500_000L : 2_000_000L);
                maxChunks = Math.max(maxChunks, cpus <= 4 ? 8 : (cpus <= 8 ? 12 : 16));
            }
        }
        if (!guiTransition && !frameHitch) {
            long floorNs = movingFast ? 1_200_000L : 500_000L;
            long sharedNs = ClientRuntime.remainingStreamingBudgetNs(budgetNs);
            budgetNs = Math.min(budgetNs, Math.max(floorNs, sharedNs));
        }
        minChunks = Math.max(1, minChunks);
        maxChunks = Math.max(minChunks, maxChunks);

        int chunksHandled = 0;
        long chunkSpentNs = 0L;

        while (!this.closed) {
            Object next = this.packetsToBeHandled.peek();
            if (next == null) {
                break;
            }
            if (!(next instanceof QueuedPacketAccess acc)) {
                return;
            }
            Packet<?> pkt = acc.chunking$packet();
            boolean isChunkPkt = pkt instanceof ClientboundLevelChunkWithLightPacket;

            if (isChunkPkt && chunksHandled >= minChunks) {
                if (chunksHandled >= maxChunks || chunkSpentNs >= budgetNs) {
                    break;
                }
            }

            Object item = this.packetsToBeHandled.poll();
            if (item == null) {
                break;
            }
            if (isChunkPkt) {
                long t0 = System.nanoTime();
                ((QueuedPacketAccess) item).chunking$handle();
                chunkSpentNs += Math.max(0L, System.nanoTime() - t0);
                chunksHandled++;
            } else {
                ((QueuedPacketAccess) item).chunking$handle();
            }
        }
        if (chunksHandled > 0) {
            ClientRuntime.consumeStreamingBudget(chunkSpentNs);
        }
        ci.cancel();
    }
}
