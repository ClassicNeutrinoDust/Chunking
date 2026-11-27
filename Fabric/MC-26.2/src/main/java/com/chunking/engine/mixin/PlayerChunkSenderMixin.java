package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.scheduler.LookAhead;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-bucket chunk sender that adapts batch size to server MSPT and sub-tick pulse rate.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    private static final float MAX_HEALTHY_CHUNKS_PER_TICK = TickBudget.LIVE_REMOTE_BATCH_CAP;
    private static final float MAX_LOCAL_CHUNKS_PER_TICK = TickBudget.LIVE_LOCAL_BATCH_CAP;
    private static final int MAX_HEALTHY_UNACK_BATCHES = 8;
    private static final int MAX_LOCAL_UNACK_BATCHES = 64;

    @Shadow
    @Final
    private LongSet pendingChunks;

    @Shadow
    @Final
    private boolean memoryConnection;

    @Shadow
    private float desiredChunksPerTick;

    @Shadow
    private float batchQuota;

    @Shadow
    private int unacknowledgedBatches;

    @Shadow
    private int maxUnacknowledgedBatches;

    @Unique
    private boolean chunking$hasPrevPos;

    @Unique
    private double chunking$prevX;

    @Unique
    private double chunking$prevZ;

    @Unique
    private double chunking$vx;

    @Unique
    private double chunking$vz;

    @Unique
    private double chunking$lookX;

    @Unique
    private double chunking$lookZ;

    @Unique
    private long chunking$lastSendNs;

    @Unique
    private long[] chunking$heapPacked;

    @Unique
    private int[] chunking$heapScore;

    @Unique
    private LevelChunk[] chunking$heapChunks;

    @Unique
    private int chunking$lastVelocityTick = -1;

    @Unique
    private Object chunking$lastLevel;

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void chunking$uncapAndTrackPlayerVelocity(ServerPlayer player, CallbackInfo ci) {
        double px = player.getX();
        double pz = player.getZ();
        Vec3 vel = player.getDeltaMovement();
        Vec3 look = player.getLookAngle();
        int playerTick = player.tickCount;
        Object curLevel = player.level();
        if (!this.chunking$hasPrevPos || curLevel != this.chunking$lastLevel) {
            this.chunking$vx = vel.x;
            this.chunking$vz = vel.z;
            this.chunking$hasPrevPos = true;
            this.chunking$lastVelocityTick = playerTick;
            this.chunking$lastLevel = curLevel;
            this.chunking$prevX = px;
            this.chunking$prevZ = pz;
        } else if (playerTick != this.chunking$lastVelocityTick) {
            double dt = Math.max(1.0D, (double) (playerTick - this.chunking$lastVelocityTick));
            this.chunking$lastVelocityTick = playerTick;
            double dx = (px - this.chunking$prevX) / dt;
            double dz = (pz - this.chunking$prevZ) / dt;
            if (dx * dx + dz * dz <= 25_600.0D) {
                this.chunking$vx = dx * 0.65D + this.chunking$vx * 0.35D;
                this.chunking$vz = dz * 0.65D + this.chunking$vz * 0.35D;
            } else {
                this.chunking$vx = vel.x;
                this.chunking$vz = vel.z;
            }
            if (vel.x * vel.x + vel.z * vel.z > this.chunking$vx * this.chunking$vx + this.chunking$vz * this.chunking$vz) {
                this.chunking$vx = vel.x;
                this.chunking$vz = vel.z;
            }
            this.chunking$prevX = px;
            this.chunking$prevZ = pz;
        }
        double lookLenSq = look.x * look.x + look.z * look.z;
        if (lookLenSq > 1e-10D) {
            double invLook = 1.0D / Math.sqrt(lookLenSq);
            this.chunking$lookX = look.x * invLook;
            this.chunking$lookZ = look.z * invLook;
        } else {
            this.chunking$lookX = 0.0D;
            this.chunking$lookZ = 1.0D;
        }

        if (!ModConfig.VELOCITY_CONE_SCHEDULER || this.pendingChunks.isEmpty()) {
            return;
        }
        float cap = this.memoryConnection ? MAX_LOCAL_CHUNKS_PER_TICK : MAX_HEALTHY_CHUNKS_PER_TICK;
        float adaptiveRate = Math.min(cap, chunking$computeAdaptiveChunksPerTick(player, this.memoryConnection));
        FrameGuard gov = FrameGuard.get();
        boolean clientActive = gov.hasActiveClientFrames();
        double lastTickMs = TickBudget.lastRecordedTickMs();
        boolean severeTick = gov.level() == FrameGuard.Level.SEVERE || lastTickMs > 35.0D;
        boolean mildTick = !severeTick
                && (gov.isServerTickPressured() || gov.level() == FrameGuard.Level.MILD || lastTickMs > 25.0D);

        if (this.memoryConnection && !clientActive && !severeTick && !mildTick
                && (player.level().getServer() == null || player.level().getServer().isDedicatedServer())) {
            adaptiveRate = Math.max(24.0F, adaptiveRate);
            this.unacknowledgedBatches = 0;
        }
        long now = System.nanoTime();
        long prev = this.chunking$lastSendNs;
        this.chunking$lastSendNs = now;
        double elapsedTicks = prev == 0L
                ? 1.0D
                : Math.max(clientActive ? 0.10D : 0.22D, Math.min(1.0D, (now - prev) / 50_000_000.0D));
        float minFloor = clientActive ? 1.0F : (severeTick ? 1.0F : (mildTick ? 2.0F : 6.0F));
        float scaledQuota = Math.max(minFloor, Math.min(cap, (float) Math.ceil(adaptiveRate * elapsedTicks)));

        if (clientActive) {
            gov.recordClientWorldSections(player.level().getSectionsCount());
            // Server send quota is governed by SERVER tick health (not client per-frame packet count),
            // because PacketProcessorMixin smoothly paces client render-thread ingestion by nanosecond timer!
            if (lastTickMs > 35.0D) {
                scaledQuota = Math.min(scaledQuota, 6.0F);
                adaptiveRate = Math.min(adaptiveRate, 12.0F);
            } else if (gov.isInitialWorldLoadWindow()) {
                scaledQuota = Math.min(scaledQuota, 12.0F);
                adaptiveRate = Math.min(adaptiveRate, 18.0F);
            } else if (gov.isServerTickPressured() || lastTickMs > 28.0D) {
                scaledQuota = Math.min(scaledQuota, 16.0F);
                adaptiveRate = Math.min(adaptiveRate, 24.0F);
            } else {
                scaledQuota = Math.max(scaledQuota, 28.0F);
                scaledQuota = Math.min(scaledQuota, MAX_LOCAL_CHUNKS_PER_TICK);
                adaptiveRate = Math.max(adaptiveRate, 32.0F);
            }
        } else if (severeTick) {
            scaledQuota = Math.min(scaledQuota, 3.0F);
            adaptiveRate = Math.min(adaptiveRate, 6.0F);
        } else if (mildTick) {
            scaledQuota = Math.min(scaledQuota, 8.0F);
            adaptiveRate = Math.min(adaptiveRate, 16.0F);
        }

        this.desiredChunksPerTick = scaledQuota;
        this.batchQuota = 0.0F;
        if (clientActive && this.memoryConnection) {
            this.maxUnacknowledgedBatches = (lastTickMs > 35.0D) ? 2 : 10;
        } else if (severeTick) {
            this.maxUnacknowledgedBatches = 2;
        } else if (mildTick) {
            this.maxUnacknowledgedBatches = 4;
        } else {
            this.maxUnacknowledgedBatches = this.memoryConnection ? MAX_LOCAL_UNACK_BATCHES : MAX_HEALTHY_UNACK_BATCHES;
        }
    }

    @Unique
    private static float chunking$computeAdaptiveChunksPerTick(ServerPlayer player, boolean localMemory) {
        if (player == null || player.level() == null) {
            return 32.0F;
        }
        var server = player.level().getServer();
        if (server == null) {
            return 32.0F;
        }
        double effectiveMs = TickBudget.measureEffectiveTickMs(server);
        return TickBudget.computeChunkSendRate(localMemory, effectiveMs);
    }

    @Inject(method = "onChunkBatchReceivedByClient", at = @At("TAIL"))
    private void chunking$preventBatchAckThrottling(float desiredChunksPerTick, CallbackInfo ci) {
        if (!ModConfig.VELOCITY_CONE_SCHEDULER || this.pendingChunks.isEmpty()) {
            return;
        }
        FrameGuard gov = FrameGuard.get();
        if (gov.hasActiveClientFrames() && this.memoryConnection) {
            double tickMs = TickBudget.lastRecordedTickMs();
            boolean calmFast = (this.chunking$vx * this.chunking$vx + this.chunking$vz * this.chunking$vz) >= 0.64D
                    && !gov.isInitialWorldLoadWindow()
                    && !gov.isServerTickPressured()
                    && tickMs <= 28.0D;
            float floor = calmFast ? 24.0F : 16.0F;
            if (this.desiredChunksPerTick < floor) {
                this.desiredChunksPerTick = floor;
            } else if (this.desiredChunksPerTick > MAX_LOCAL_CHUNKS_PER_TICK) {
                this.desiredChunksPerTick = MAX_LOCAL_CHUNKS_PER_TICK;
            }
            this.maxUnacknowledgedBatches = tickMs > 35.0D ? 2 : (calmFast ? 10 : 6);
            return;
        }
        double lastTickMs = TickBudget.lastRecordedTickMs();
        boolean severeTick = gov.level() == FrameGuard.Level.SEVERE || lastTickMs > 35.0D;
        boolean mildTick = !severeTick
                && (gov.isServerTickPressured() || gov.level() == FrameGuard.Level.MILD || lastTickMs > 25.0D);

        float cap = this.memoryConnection ? MAX_LOCAL_CHUNKS_PER_TICK : MAX_HEALTHY_CHUNKS_PER_TICK;
        float floorRate;
        if (severeTick) {
            cap = Math.min(cap, 6.0F);
            floorRate = 3.0F;
        } else if (mildTick) {
            cap = Math.min(cap, 16.0F);
            floorRate = 8.0F;
        } else {
            floorRate = this.memoryConnection ? 24.0F : 16.0F;
        }
        if (this.desiredChunksPerTick < floorRate) {
            this.desiredChunksPerTick = floorRate;
        } else if (this.desiredChunksPerTick > cap) {
            this.desiredChunksPerTick = cap;
        }
        if (this.memoryConnection && !ModConfig.isClientEnvironment() && !severeTick && !mildTick) {
            this.unacknowledgedBatches = 0;
        }
        if (severeTick) {
            this.maxUnacknowledgedBatches = 2;
        } else if (mildTick) {
            this.maxUnacknowledgedBatches = 4;
        } else {
            this.maxUnacknowledgedBatches = this.memoryConnection ? MAX_LOCAL_UNACK_BATCHES : MAX_HEALTHY_UNACK_BATCHES;
        }
    }

    @Unique
    private static void chunking$siftDownMax(int[] scores, long[] packed, LevelChunk[] chunks, int size, int idx) {
        int s = scores[idx];
        long p = packed[idx];
        LevelChunk c = chunks[idx];
        int half = size >>> 1;
        while (idx < half) {
            int child = (idx << 1) + 1;
            int cScore = scores[child];
            int right = child + 1;
            if (right < size && scores[right] > cScore) {
                child = right;
                cScore = scores[right];
            }
            if (s >= cScore) {
                break;
            }
            scores[idx] = cScore;
            packed[idx] = packed[child];
            chunks[idx] = chunks[child];
            idx = child;
        }
        scores[idx] = s;
        packed[idx] = p;
        chunks[idx] = c;
    }

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
    private void chunking$collectVelocitySortedChunks(
            ChunkMap chunkMap,
            ChunkPos playerPos,
            CallbackInfoReturnable<List<LevelChunk>> cir
    ) {
        if (!ModConfig.VELOCITY_CONE_SCHEDULER || this.pendingChunks.isEmpty()) {
            return;
        }

        int limit = Mth.floor(this.batchQuota);
        int pendingCount = this.pendingChunks.size();
        int takeLimit = Math.min(limit, pendingCount);
        if (takeLimit <= 0) {
            cir.setReturnValue(List.of());
            return;
        }

        int cap = takeLimit;
        if (this.chunking$heapPacked == null || this.chunking$heapPacked.length < cap) {
            int curLen = this.chunking$heapPacked == null ? 128 : this.chunking$heapPacked.length;
            int newCap = Math.max(cap, curLen << 1);
            this.chunking$heapPacked = new long[newCap];
            this.chunking$heapScore = new int[newCap];
            this.chunking$heapChunks = new LevelChunk[newCap];
        }
        long[] hPacked = this.chunking$heapPacked;
        int[] hScore = this.chunking$heapScore;
        LevelChunk[] hChunks = this.chunking$heapChunks;

        int pcx = playerPos.x();
        int pcz = playerPos.z();
        double vx = this.chunking$vx;
        double vz = this.chunking$vz;
        double lx = this.chunking$lookX;
        double lz = this.chunking$lookZ;

        // Hoist normalized heading vector and bias once before iterating pendingChunks (zero Math.hypot)
        double lookLenSq = lx * lx + lz * lz;
        if (lookLenSq > 1e-10D) {
            double invLook = 1.0D / Math.sqrt(lookLenSq);
            lx *= invLook;
            lz *= invLook;
        } else {
            lx = 0.0D;
            lz = 1.0D;
        }
        double speedSq = vx * vx + vz * vz;
        double hx;
        double hz;
        double bias;
        if (speedSq >= 4.0D) {
            // Very fast flight (>= 40 b/s): prioritize trajectory ahead over momentary look
            double invSpeed = 1.0D / Math.sqrt(speedSq);
            double bx = 0.40D * lx + 0.60D * (vx * invSpeed);
            double bz = 0.40D * lz + 0.60D * (vz * invSpeed);
            double bLenSq = bx * bx + bz * bz;
            if (bLenSq > 1e-10D) {
                double invB = 1.0D / Math.sqrt(bLenSq);
                hx = bx * invB;
                hz = bz * invB;
            } else {
                hx = vx * invSpeed;
                hz = vz * invSpeed;
            }
            bias = 0.42D;
        } else if (speedSq >= LookAhead.HIGH_SPEED_THRESHOLD * LookAhead.HIGH_SPEED_THRESHOLD) {
            double invSpeed = 1.0D / Math.sqrt(speedSq);
            double bx = 0.70D * lx + 0.30D * (vx * invSpeed);
            double bz = 0.70D * lz + 0.30D * (vz * invSpeed);
            double bLenSq = bx * bx + bz * bz;
            if (bLenSq > 1e-10D) {
                double invB = 1.0D / Math.sqrt(bLenSq);
                hx = bx * invB;
                hz = bz * invB;
            } else {
                hx = lx;
                hz = lz;
            }
            bias = 0.32D;
        } else {
            hx = lx;
            hz = lz;
            bias = 0.22D;
        }

        LongIterator it = this.pendingChunks.iterator();
        int filled = 0;
        while (it.hasNext()) {
            long packed = it.nextLong();
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            int score = LookAhead.computeSendSortScoreWithHeading(pcx, pcz, hx, hz, bias, cx, cz);
            if (filled == cap && score >= hScore[0]) {
                continue;
            }
            LevelChunk chunk = chunkMap.getChunkToSend(packed);
            if (chunk == null) {
                continue;
            }
            if (filled < cap) {
                hPacked[filled] = packed;
                hScore[filled] = score;
                hChunks[filled] = chunk;
                filled++;
                if (filled == cap) {
                    for (int i = (filled >>> 1) - 1; i >= 0; i--) {
                        chunking$siftDownMax(hScore, hPacked, hChunks, filled, i);
                    }
                }
            } else {
                hScore[0] = score;
                hPacked[0] = packed;
                hChunks[0] = chunk;
                chunking$siftDownMax(hScore, hPacked, hChunks, filled, 0);
            }
        }

        if (filled == 0) {
            cir.setReturnValue(List.of());
            return;
        }
        if (filled < cap) {
            for (int i = (filled >>> 1) - 1; i >= 0; i--) {
                chunking$siftDownMax(hScore, hPacked, hChunks, filled, i);
            }
        }
        for (int end = filled - 1; end > 0; end--) {
            int maxS = hScore[0];
            long maxP = hPacked[0];
            LevelChunk maxC = hChunks[0];
            hScore[0] = hScore[end];
            hPacked[0] = hPacked[end];
            hChunks[0] = hChunks[end];
            hScore[end] = maxS;
            hPacked[end] = maxP;
            hChunks[end] = maxC;
            chunking$siftDownMax(hScore, hPacked, hChunks, end, 0);
        }

        List<LevelChunk> ready = new ArrayList<>(filled);
        for (int i = 0; i < filled; i++) {
            LevelChunk chunk = hChunks[i];
            hChunks[i] = null;
            if (chunk != null) {
                ready.add(chunk);
                this.pendingChunks.remove(hPacked[i]);
            }
        }
        if (!ready.isEmpty()) {
            com.chunking.engine.core.LiveStats.get().recordChunksSent(ready.size());
        }
        cir.setReturnValue(ready);
    }
}
