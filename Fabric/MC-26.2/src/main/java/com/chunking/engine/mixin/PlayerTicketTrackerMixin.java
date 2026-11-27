package com.chunking.engine.mixin;

import com.chunking.engine.mixin.accessor.DistanceManagerAccessor;
import com.chunking.engine.mixin.accessor.FixedDistanceTrackerAccessor;
import com.chunking.engine.scheduler.TicketRing;
import com.chunking.engine.access.PlayerTicketAccess;
import com.chunking.engine.access.TaskDispatcherAccess;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Replaces {@code DistanceManager$PlayerTicketTracker}'s 2-pass BFS flood-fill
 * ({@code DynamicGraphMinFixedPoint.runUpdates}) with {@link TicketRing},
 * eliminating O(16,641) graph scans on every chunk crossing during fast flight.
 */
@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlayerTicketTrackerMixin implements PlayerTicketAccess {
    @Shadow
    private int viewDistance;

    @Shadow
    @Final
    private Long2IntMap queueLevels;

    @Shadow
    @Final
    private LongSet toUpdate;

    @Shadow
    protected abstract void onLevelChange(long pos, int level, boolean hadTicket, boolean hasTicket);

    // Non-static @Unique fields must have no inline initializers (JEP 513 / Mixin 0.8.7).
    @Unique
    private DistanceManager chunking$owner;

    @Unique
    private TicketRing chunking$engine;

    @Unique
    private long[] chunking$sourceScratch;

    @Unique
    private boolean chunking$dirty;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$captureOwner(DistanceManager owner, int maxDistance, CallbackInfo ci) {
        this.chunking$owner = owner;
    }

    @Override
    public void chunking$markDirty() {
        this.chunking$dirty = true;
    }

    @Inject(method = "runAllUpdates", at = @At("HEAD"), cancellable = true)
    private void chunking$analyticalRunAllUpdates(CallbackInfo ci) {
        if (this.chunking$owner == null) {
            return;
        }
        chunking$sync(false);
        ci.cancel();
    }

    @Inject(method = "updateViewDistance", at = @At("HEAD"), cancellable = true)
    private void chunking$analyticalUpdateViewDistance(int newViewDistance, CallbackInfo ci) {
        if (this.chunking$owner == null) {
            return;
        }
        if (newViewDistance == this.viewDistance && !this.chunking$dirty) {
            ci.cancel();
            return;
        }
        this.viewDistance = newViewDistance;
        chunking$sync(true);
        ci.cancel();
    }

    @Unique
    private void chunking$sync(boolean force) {
        TicketRing engine = this.chunking$engine;
        if (engine == null) {
            engine = new TicketRing();
            this.chunking$engine = engine;
        }
        DistanceManagerAccessor ownerAcc = (DistanceManagerAccessor) this.chunking$owner;
        Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk = ownerAcc.chunking$getPlayersPerChunk();
        if (!force && !this.chunking$dirty && playersPerChunk.isEmpty() && engine.lastSourceCount() == 0) {
            return;
        }
        if (!force && !this.chunking$dirty && playersPerChunk.size() == engine.lastSourceCount() && !playersPerChunk.isEmpty()) {
            // Fast no-op when no addPlayer/removePlayer touched PlayerTicketTracker since last sync.
            return;
        }

        long[] sources = this.chunking$sourceScratch;
        if (sources == null) {
            sources = new long[8];
            this.chunking$sourceScratch = sources;
        }
        int count = 0;
        ObjectIterator<Long2ObjectMap.Entry<ObjectSet<ServerPlayer>>> it = Long2ObjectMaps.fastIterator(playersPerChunk);
        while (it.hasNext()) {
            Long2ObjectMap.Entry<ObjectSet<ServerPlayer>> entry = it.next();
            ObjectSet<ServerPlayer> set = entry.getValue();
            if (set == null || set.isEmpty()) {
                continue;
            }
            if (count == sources.length) {
                sources = Arrays.copyOf(sources, sources.length << 1);
                this.chunking$sourceScratch = sources;
            }
            sources[count++] = entry.getLongKey();
        }

        FixedDistanceTrackerAccessor baseAcc = (FixedDistanceTrackerAccessor) this;
        Long2ByteMap chunks = baseAcc.chunking$getChunks();
        int maxDistance = baseAcc.chunking$getMaxDistance();
        int defaultLevel = maxDistance + 2;
        ThrottlingChunkTaskDispatcher dispatcher = ownerAcc.chunking$getTicketDispatcher();
        TaskDispatcherAccess dispatcherAcc =
                dispatcher instanceof TaskDispatcherAccess acc ? acc : null;

        engine.sync(
                sources,
                count,
                this.viewDistance,
                maxDistance,
                chunks,
                this.queueLevels,
                pos -> dispatcherAcc != null && dispatcherAcc.chunking$isPosActive(pos),
                (pos, newLvl) -> dispatcher.onLevelChange(
                        ChunkPos.unpack(pos),
                        () -> this.queueLevels.get(pos),
                        newLvl,
                        lvl -> {
                            if (lvl == defaultLevel) {
                                this.queueLevels.remove(pos);
                            } else {
                                this.queueLevels.put(pos, lvl);
                            }
                        }),
                this::onLevelChange
        );
        this.chunking$dirty = false;
        this.toUpdate.clear();
    }
}
