package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.scheduler.LookAhead;
import com.chunking.engine.scheduler.MoveTracker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Clamps priority indices for >32 view distances and pops the best velocity-cone chunk across buckets.
 */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class PriorityQueueMixin {
    private static final int MAX_BUCKET_LOOKAHEAD = 28;
    private static final int MAX_PER_BUCKET_SCAN = 192;
    private static final int MAX_TOTAL_SCAN = 512;

    @Shadow
    @Final
    private List<Long2ObjectLinkedOpenHashMap<List<Runnable>>> queuesPerPriority;

    @Shadow
    private volatile int topPriorityQueueIndex;

    @Shadow
    public abstract boolean hasWork();

    @ModifyVariable(method = "submit", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int chunking$clampSubmitPriority(int priority) {
        int maxIdx = this.queuesPerPriority.size() - 1;
        return priority < 0 ? 0 : Math.min(maxIdx, priority);
    }

    @WrapMethod(method = "resortChunkTasks")
    private void chunking$clampResortChunkTasks(
            int oldPriority,
            ChunkPos pos,
            int newPriority,
            Operation<Void> original
    ) {
        int maxIdx = this.queuesPerPriority.size() - 1;
        if (maxIdx < 0) {
            return;
        }
        int clampedOld = oldPriority < 0 ? 0 : Math.min(maxIdx, oldPriority);
        int clampedNew = newPriority < 0 ? 0 : Math.min(maxIdx, newPriority);
        if (clampedOld == clampedNew) {
            return;
        }
        original.call(clampedOld, pos, clampedNew);
    }

    @WrapMethod(method = "pop")
    private ChunkTaskPriorityQueue.TasksForChunk chunking$popNearestToPlayer(
            Operation<ChunkTaskPriorityQueue.TasksForChunk> original
    ) {
        if (!ModConfig.VELOCITY_CONE_SCHEDULER || !this.hasWork()) {
            return original.call();
        }
        MoveTracker.PlayerMotion[] motions = ChunkEngine.get().velocityTracker().getAnyActiveMotions();
        if (motions == null || motions.length == 0 || motions[0] == null) {
            return original.call();
        }
        MoveTracker.PlayerMotion primary = motions[0];
        int pcx = ((int) Math.floor(primary.x)) >> 4;
        int pcz = ((int) Math.floor(primary.z)) >> 4;

        // Hoist normalized heading vector and bias once before scanning buckets (zero Math.hypot)
        double lookX = primary.lookX;
        double lookZ = primary.lookZ;
        double lookLenSq = lookX * lookX + lookZ * lookZ;
        double lx;
        double lz;
        if (lookLenSq > 1e-10D) {
            double invLook = 1.0D / Math.sqrt(lookLenSq);
            lx = lookX * invLook;
            lz = lookZ * invLook;
        } else {
            lx = 0.0D;
            lz = 1.0D;
        }
        double vx = primary.vx;
        double vz = primary.vz;
        double speedSq = vx * vx + vz * vz;
        double hx;
        double hz;
        double bias;
        if (speedSq >= 4.0D) {
            // Very fast flight (>= 40 b/s): strong 0.55 forward-cone bias weighted 60% velocity / 40% look
            // so chunks 20-32 chunks directly ahead win over closer side/rear chunks at 24-32 RD.
            double invSpeed = 1.0D / Math.sqrt(speedSq);
            double bx = 0.40D * lx + 0.60D * (vx * invSpeed);
            double bz = 0.40D * lz + 0.60D * (vz * invSpeed);
            double bLenSq = bx * bx + bz * bz;
            if (bLenSq > 1e-10D) {
                double invB = 1.0D / Math.sqrt(bLenSq);
                hx = bx * invB;
                hz = bz * invB;
            } else {
                hx = lx;
                hz = lz;
            }
            bias = 0.55D;
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

        int topIdx = this.topPriorityQueueIndex;
        int nLevels = this.queuesPerPriority.size();
        if (topIdx >= nLevels) {
            return original.call();
        }
        int maxBucket = Math.min(nLevels - 1, topIdx + MAX_BUCKET_LOOKAHEAD);

        int bestBucket = -1;
        long bestKey = 0L;
        int bestScore = Integer.MAX_VALUE;
        int totalScanned = 0;

        for (int b = topIdx; b <= maxBucket && totalScanned < MAX_TOTAL_SCAN; b++) {
            Long2ObjectLinkedOpenHashMap<List<Runnable>> map = this.queuesPerPriority.get(b);
            if (map.isEmpty()) {
                continue;
            }
            int levelPenalty = (b - topIdx) * 4;
            int bucketScanned = 0;
            LongIterator it = map.keySet().longIterator();
            while (it.hasNext() && bucketScanned++ < MAX_PER_BUCKET_SCAN && totalScanned++ < MAX_TOTAL_SCAN) {
                long packed = it.nextLong();
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                int coneScore = LookAhead.computeSendSortScoreWithHeading(
                        pcx, pcz, hx, hz, bias, cx, cz);
                for (int mi = 1; mi < motions.length; mi++) {
                    MoveTracker.PlayerMotion m = motions[mi];
                    if (m == null) continue;
                    int mcx = ((int) Math.floor(m.x)) >> 4;
                    int mcz = ((int) Math.floor(m.z)) >> 4;
                    int s = LookAhead.computeSendSortScore(
                            mcx, mcz, m.vx, m.vz, m.lookX, m.lookZ, cx, cz);
                    if (s < coneScore) {
                        coneScore = s;
                    }
                }
                int score = (int) Math.min((long) Integer.MAX_VALUE, (long) coneScore + (long) levelPenalty);
                if (bestBucket < 0 || score < bestScore) {
                    bestScore = score;
                    bestKey = packed;
                    bestBucket = b;
                    if (score <= 4) {
                        break;
                    }
                }
            }
            if (bestScore <= 4) {
                break;
            }
        }

        if (bestBucket < 0) {
            return original.call();
        }
        List<Runnable> tasks = this.queuesPerPriority.get(bestBucket).remove(bestKey);
        if (tasks == null) {
            return original.call();
        }
        while (this.hasWork() && this.queuesPerPriority.get(this.topPriorityQueueIndex).isEmpty()) {
            this.topPriorityQueueIndex++;
        }
        return new ChunkTaskPriorityQueue.TasksForChunk(bestKey, tasks);
    }
}
