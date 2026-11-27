package com.chunking.engine.mixin;

import com.chunking.engine.mixin.accessor.DataLayerStorageMapAccessor;
import com.chunking.engine.mixin.accessor.SkyDataLayerStorageMapAccessor;
import com.chunking.engine.access.LightUpdatingAccess;
import com.chunking.engine.access.LightVisibleLockAccess;
import com.chunking.engine.world.LightMaps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.locks.StampedLock;

/**
 * Eliminates full-map {@code Long2ObjectOpenHashMap.clone()} and {@code Long2IntOpenHashMap.clone()}
 * copies in {@link LayerLightSectionStorage#swapSectionMap()}, uses {@link StampedLock} optimistic reads
 * over visible light tables so client meshing + Render threads never contend on atomic CAS locks,
 * maintains {@code O(|changed|)} queued section tracking in {@code markNewInconsistencies}, and
 * caches the active write {@link DataLayer} without {@code LongConsumer} lambda allocations in {@code setStoredLevel}.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin<M extends DataLayerStorageMap<M>>
        implements LightVisibleLockAccess, LightUpdatingAccess {

    @Shadow
    @Final
    private LightLayer layer;

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Shadow
    protected volatile M visibleSectionData;

    @Shadow
    @Final
    protected M updatingSectionData;

    @Shadow
    @Final
    protected LongSet changedSections;

    @Shadow
    @Final
    protected LongSet sectionsAffectedByLightUpdates;

    @Shadow
    @Final
    protected Long2ObjectMap<DataLayer> queuedSections;

    @Shadow
    @Final
    private LongSet columnsToRetainQueuedDataFor;

    @Shadow
    @Final
    private LongSet toRemove;

    @Shadow
    protected volatile boolean hasInconsistencies;

    @Shadow
    protected abstract DataLayer getDataLayer(M map, long sectionPos);

    @Shadow
    protected abstract void onNodeRemoved(long sectionPos);

    // Initialized in <init> (mixin field)
    @Unique
    private StampedLock chunking$visibleLock;

    @Unique
    private volatile LightMaps.ObjTable chunking$visObjTable;

    @Unique
    private volatile LightMaps.IntTable chunking$visTopTable;

    @Unique
    private volatile int chunking$visLowestY;

    @Unique
    private long chunking$lastWrittenSecPos;

    @Unique
    private DataLayer chunking$lastWrittenLayer;

    @Unique
    private long chunking$lastReadSecPos;

    @Unique
    private DataLayer chunking$lastReadLayer;

    @Unique
    private LongOpenHashSet chunking$dirtySkyCols;

    @Unique
    private Long2ObjectOpenHashMap<DataLayer> chunking$rawQueuedSections;

    @Unique
    private LongOpenHashSet chunking$queuedPendingCheck;

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMaps;synchronize(Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;)Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;"
            )
    )
    @SuppressWarnings("unchecked")
    private Long2ObjectMap<DataLayer> chunking$captureRawQueuedMap(
            Long2ObjectMap<DataLayer> raw,
            Operation<Long2ObjectMap<DataLayer>> original
    ) {
        if (raw instanceof Long2ObjectOpenHashMap<DataLayer> open) {
            this.chunking$rawQueuedSections = open;
        }
        return original.call(raw);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initOptimisticTables(CallbackInfo ci) {
        this.chunking$visibleLock = new StampedLock();
        this.chunking$visObjTable = new LightMaps.ObjTable(64);
        this.chunking$visTopTable = new LightMaps.IntTable(64, Integer.MAX_VALUE);
        this.chunking$visLowestY = Integer.MAX_VALUE;
        this.chunking$lastWrittenSecPos = Long.MAX_VALUE;
        this.chunking$lastReadSecPos = Long.MAX_VALUE;
        this.chunking$queuedPendingCheck = new LongOpenHashSet(64);
        if ((Object) this.visibleSectionData instanceof SkyDataLayerStorageMapAccessor visSky) {
            int lowestY = visSky.chunking$getCurrentLowestY();
            this.chunking$visLowestY = lowestY;
            this.chunking$visTopTable.setDefaultReturnValue(lowestY);
        }
    }

    @Inject(
            method = "getDataLayer(Lnet/minecraft/world/level/lighting/DataLayerStorageMap;J)Lnet/minecraft/world/level/chunk/DataLayer;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$readVisibleLayerSafely(M map, long sectionPos, CallbackInfoReturnable<DataLayer> cir) {
        if (map != this.visibleSectionData) {
            return;
        }
        long stamp = this.chunking$visibleLock.tryOptimisticRead();
        LightMaps.ObjTable tbl = this.chunking$visObjTable;
        DataLayer dl = tbl != null ? tbl.getOptimistic(sectionPos) : null;
        if (!this.chunking$visibleLock.validate(stamp)) {
            long rs = this.chunking$visibleLock.readLock();
            try {
                tbl = this.chunking$visObjTable;
                dl = tbl != null ? tbl.getOptimistic(sectionPos) : null;
            } finally {
                this.chunking$visibleLock.unlockRead(rs);
            }
        }
        cir.setReturnValue(dl);
    }

    @Override
    public StampedLock chunking$visibleLock() {
        return this.chunking$visibleLock;
    }

    @Override
    public LightMaps.ObjTable chunking$getVisObjTable() {
        return this.chunking$visObjTable;
    }

    @Override
    public LightMaps.IntTable chunking$getVisTopTable() {
        return this.chunking$visTopTable;
    }

    @Override
    public int chunking$getVisLowestY() {
        return this.chunking$visLowestY;
    }

    @Override
    public void chunking$recordDirtySkyColumn(long colZeroNode) {
        LongOpenHashSet cols = this.chunking$dirtySkyCols;
        if (cols == null) {
            cols = new LongOpenHashSet(32);
            this.chunking$dirtySkyCols = cols;
        }
        cols.add(colZeroNode);
    }

    /**
     * @author Chunking
     * @reason Direct lock-free updatingSectionData lookup in storingLightForSection without getDataLayer(long, boolean) dispatch.
     */
    @Overwrite
    protected boolean storingLightForSection(long sectionPos) {
        return this.updatingSectionData.getLayer(sectionPos) != null;
    }

    @Override
    public DataLayer chunking$getUpdatingLayer(long secPos) {
        if (secPos == this.chunking$lastWrittenSecPos && this.chunking$lastWrittenLayer != null) {
            return this.chunking$lastWrittenLayer;
        }
        if (secPos == this.chunking$lastReadSecPos) {
            return this.chunking$lastReadLayer;
        }
        DataLayer dl = this.updatingSectionData.getLayer(secPos);
        this.chunking$lastReadSecPos = secPos;
        this.chunking$lastReadLayer = dl;
        return dl;
    }

    @Override
    public DataLayer chunking$getOrCopyWritableLayer(long secPos) {
        if (secPos == this.chunking$lastWrittenSecPos && this.chunking$lastWrittenLayer != null) {
            return this.chunking$lastWrittenLayer;
        }
        DataLayer dataLayer = this.changedSections.add(secPos)
                ? this.updatingSectionData.copyDataLayer(secPos)
                : this.updatingSectionData.getLayer(secPos);
        this.chunking$lastWrittenSecPos = secPos;
        this.chunking$lastWrittenLayer = dataLayer;
        this.chunking$lastReadSecPos = secPos;
        this.chunking$lastReadLayer = dataLayer;
        this.sectionsAffectedByLightUpdates.add(secPos);
        return dataLayer;
    }

    @Override
    public int chunking$getStoredLevelFast(long blockPos) {
        long secPos = SectionPos.blockToSection(blockPos);
        DataLayer dl = chunking$getUpdatingLayer(secPos);
        if (dl == null) {
            return 0;
        }
        return dl.get(
                SectionPos.sectionRelative(BlockPos.getX(blockPos)),
                SectionPos.sectionRelative(BlockPos.getY(blockPos)),
                SectionPos.sectionRelative(BlockPos.getZ(blockPos))
        );
    }

    @Override
    public void chunking$setStoredLevelFast(long blockPos, int level) {
        this.setStoredLevel(blockPos, level);
    }

    @Override
    public int chunking$getUpdatingTopSectionY(long zeroNode) {
        return (Object) this.updatingSectionData instanceof SkyDataLayerStorageMapAccessor sky
                ? sky.chunking$getTopSections().get(zeroNode)
                : 0;
    }

    @Override
    public int chunking$getUpdatingBottomSectionY() {
        return (Object) this.updatingSectionData instanceof SkyDataLayerStorageMapAccessor sky
                ? sky.chunking$getCurrentLowestY()
                : 0;
    }

    @Override
    public void chunking$markBorderAffected(int bx, int by, int bz) {
        chunking$markNeighborBorders(bx, by, bz);
    }

    @Unique
    private void chunking$markNeighborBorders(int bx, int by, int bz) {
        int lx = bx & 15;
        int ly = by & 15;
        int lz = bz & 15;
        int ox = lx == 0 ? -1 : (lx == 15 ? 1 : 0);
        int oy = ly == 0 ? -1 : (ly == 15 ? 1 : 0);
        int oz = lz == 0 ? -1 : (lz == 15 ? 1 : 0);
        int sx = bx >> 4;
        int sy = by >> 4;
        int sz = bz >> 4;
        LongSet affected = this.sectionsAffectedByLightUpdates;
        if (oy == 0 && oz == 0) {
            if (ox != 0) {
                affected.add(SectionPos.asLong(sx + ox, sy, sz));
            }
            return;
        }
        if (ox == 0 && oz == 0) {
            if (oy != 0) {
                affected.add(SectionPos.asLong(sx, sy + oy, sz));
            }
            return;
        }
        if (ox == 0 && oy == 0) {
            if (oz != 0) {
                affected.add(SectionPos.asLong(sx, sy, sz + oz));
            }
            return;
        }
        int minSx = ox < 0 ? sx - 1 : sx;
        int maxSx = ox > 0 ? sx + 1 : sx;
        int minSy = oy < 0 ? sy - 1 : sy;
        int maxSy = oy > 0 ? sy + 1 : sy;
        int minSz = oz < 0 ? sz - 1 : sz;
        int maxSz = oz > 0 ? sz + 1 : sz;
        for (int x = minSx; x <= maxSx; x++) {
            for (int y = minSy; y <= maxSy; y++) {
                for (int z = minSz; z <= maxSz; z++) {
                    affected.add(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    /**
     * @author Chunking
     * @reason Lock-free reads on updatingSectionData and StampedLock optimistic reads over visible light snapshots.
     */
    @Overwrite
    protected DataLayer getDataLayer(long sectionPos, boolean cached) {
        if (cached) {
            return chunking$getUpdatingLayer(sectionPos);
        }
        StampedLock sl = this.chunking$visibleLock;
        long stamp = sl.tryOptimisticRead();
        DataLayer dl = stamp != 0L ? this.chunking$visObjTable.getOptimistic(sectionPos) : null;
        if (!sl.validate(stamp)) {
            long rs = sl.readLock();
            try {
                dl = this.chunking$visObjTable.getOptimistic(sectionPos);
            } finally {
                sl.unlockRead(rs);
            }
        }
        return dl;
    }

    /**
     * @author Chunking
     * @reason Last-written section DataLayer cache plus inline border-section notification without LongConsumer lambda allocation.
     */
    @Overwrite
    protected void setStoredLevel(long blockPos, int level) {
        long secPos = SectionPos.blockToSection(blockPos);
        int bx = BlockPos.getX(blockPos);
        int by = BlockPos.getY(blockPos);
        int bz = BlockPos.getZ(blockPos);
        int lx = SectionPos.sectionRelative(bx);
        int ly = SectionPos.sectionRelative(by);
        int lz = SectionPos.sectionRelative(bz);
        boolean interior = lx > 0 && lx < 15 && ly > 0 && ly < 15 && lz > 0 && lz < 15;

        DataLayer dataLayer;
        if (secPos == this.chunking$lastWrittenSecPos && this.chunking$lastWrittenLayer != null) {
            dataLayer = this.chunking$lastWrittenLayer;
            dataLayer.set(lx, ly, lz, level);
            if (interior) {
                return;
            }
        } else {
            dataLayer = this.changedSections.add(secPos)
                    ? this.updatingSectionData.copyDataLayer(secPos)
                    : this.updatingSectionData.getLayer(secPos);
            this.chunking$lastWrittenSecPos = secPos;
            this.chunking$lastWrittenLayer = dataLayer;
            this.chunking$lastReadSecPos = secPos;
            this.chunking$lastReadLayer = dataLayer;
            dataLayer.set(lx, ly, lz, level);
            this.sectionsAffectedByLightUpdates.add(secPos);
            if (interior) {
                return;
            }
        }

        chunking$markNeighborBorders(bx, by, bz);
    }

    /**
     * @author Chunking
     * @reason Track dirty queued section keys incrementally so markNewInconsistencies is O(|changed|) instead of O(|queuedSections|).
     */
    @Overwrite
    protected void queueSectionData(long sectionPos, DataLayer data) {
        Long2ObjectOpenHashMap<DataLayer> raw = this.chunking$rawQueuedSections;
        if (raw != null) {
            synchronized (this.queuedSections) {
                if (data != null) {
                    raw.put(sectionPos, data);
                    this.chunking$queuedPendingCheck.add(sectionPos);
                    this.hasInconsistencies = true;
                } else {
                    raw.remove(sectionPos);
                    this.chunking$queuedPendingCheck.remove(sectionPos);
                }
            }
            return;
        }
        if (data != null) {
            this.queuedSections.put(sectionPos, data);
            this.hasInconsistencies = true;
        } else {
            this.queuedSections.remove(sectionPos);
        }
    }

    @Unique
    private void chunking$clearSectionCaches() {
        this.chunking$lastWrittenSecPos = Long.MAX_VALUE;
        this.chunking$lastWrittenLayer = null;
        this.chunking$lastReadSecPos = Long.MAX_VALUE;
        this.chunking$lastReadLayer = null;
    }

    @Inject(method = "getDataLayerToWrite", at = @At("RETURN"))
    private void chunking$invalidateReadCacheOnWriteCopy(long sectionPos, CallbackInfoReturnable<DataLayer> cir) {
        chunking$clearSectionCaches();
    }

    @Inject(method = "initializeSection", at = @At("TAIL"))
    private void chunking$trackInitializedQueuedSection(long sectionPos, CallbackInfo ci) {
        chunking$clearSectionCaches();
        Long2ObjectOpenHashMap<DataLayer> raw = this.chunking$rawQueuedSections;
        if (raw != null) {
            synchronized (this.queuedSections) {
                if (!raw.isEmpty() && raw.containsKey(sectionPos)) {
                    this.chunking$queuedPendingCheck.add(sectionPos);
                    this.hasInconsistencies = true;
                }
            }
        }
    }

    /**
     * @author Chunking
     * @reason Iterate only newly queued or newly initialized section keys in O(|changed|) instead of rescanning retained unloaded sections every light update.
     */
    @Overwrite
    protected void markNewInconsistencies(LightEngine<M, ?> lightEngine) {
        if (!this.hasInconsistencies) {
            return;
        }
        chunking$clearSectionCaches();
        this.hasInconsistencies = false;
        if (!this.toRemove.isEmpty()) {
            synchronized (this.queuedSections) {
                LongIterator toRemIt = this.toRemove.iterator();
                while (toRemIt.hasNext()) {
                    long pos = toRemIt.nextLong();
                    DataLayer queued = this.queuedSections.remove(pos);
                    DataLayer existing = this.updatingSectionData.removeLayer(pos);
                    if (this.columnsToRetainQueuedDataFor.contains(SectionPos.getZeroNode(pos))) {
                        if (queued != null) {
                            this.queuedSections.put(pos, queued);
                        } else if (existing != null) {
                            this.queuedSections.put(pos, existing);
                        }
                    }
                }
            }
            this.updatingSectionData.clearCache();
            LongIterator remIt2 = this.toRemove.iterator();
            while (remIt2.hasNext()) {
                long pos = remIt2.nextLong();
                this.onNodeRemoved(pos);
                this.changedSections.add(pos);
            }
            this.toRemove.clear();
        }

        Long2ObjectOpenHashMap<DataLayer> raw = this.chunking$rawQueuedSections;
        if (raw != null) {
            synchronized (this.queuedSections) {
                if (!this.chunking$queuedPendingCheck.isEmpty()) {
                    LongIterator pendingIt = this.chunking$queuedPendingCheck.iterator();
                    while (pendingIt.hasNext()) {
                        long pos = pendingIt.nextLong();
                        if (this.updatingSectionData.getLayer(pos) == null) {
                            continue;
                        }
                        DataLayer dl = raw.remove(pos);
                        if (dl != null && this.updatingSectionData.getLayer(pos) != dl) {
                            this.updatingSectionData.setLayer(pos, dl);
                            this.changedSections.add(pos);
                        }
                    }
                    this.chunking$queuedPendingCheck.clear();
                }
            }
        } else {
            ObjectIterator<Long2ObjectMap.Entry<DataLayer>> it = Long2ObjectMaps.fastIterator(this.queuedSections);
            while (it.hasNext()) {
                Long2ObjectMap.Entry<DataLayer> entry = it.next();
                long pos = entry.getLongKey();
                if (!this.storingLightForSection(pos)) {
                    continue;
                }
                DataLayer dl = entry.getValue();
                if (this.updatingSectionData.getLayer(pos) != dl) {
                    this.updatingSectionData.setLayer(pos, dl);
                    this.changedSections.add(pos);
                }
                it.remove();
            }
        }
        this.updatingSectionData.clearCache();
        chunking$clearSectionCaches();
    }

    /**
     * @author Chunking
     * @reason Incrementally patch changed sections and dirty sky columns onto visibleSectionData and StampedLock optimistic tables without full-map clones.
     */
    @Overwrite
    protected void swapSectionMap() {
        chunking$clearSectionCaches();

        boolean hasChangedSections = !this.changedSections.isEmpty();
        LongOpenHashSet dirtyCols = this.chunking$dirtySkyCols;
        boolean hasDirtyCols = dirtyCols != null && !dirtyCols.isEmpty();

        if (hasChangedSections || hasDirtyCols) {
            long ws = this.chunking$visibleLock.writeLock();
            try {
                M upd = this.updatingSectionData;
                M vis = this.visibleSectionData;
                Long2ObjectOpenHashMap<DataLayer> updMap = ((DataLayerStorageMapAccessor) (Object) upd).chunking$getMap();
                Long2ObjectOpenHashMap<DataLayer> visMap = ((DataLayerStorageMapAccessor) (Object) vis).chunking$getMap();
                LightMaps.ObjTable visObj = this.chunking$visObjTable;

                boolean isSky = (Object) vis instanceof SkyDataLayerStorageMapAccessor;
                SkyDataLayerStorageMapAccessor visSky = isSky ? (SkyDataLayerStorageMapAccessor) (Object) vis : null;
                SkyDataLayerStorageMapAccessor updSky = isSky ? (SkyDataLayerStorageMapAccessor) (Object) upd : null;
                Long2IntOpenHashMap updTop = isSky ? updSky.chunking$getTopSections() : null;
                Long2IntOpenHashMap visTop = isSky ? visSky.chunking$getTopSections() : null;
                LightMaps.IntTable visTopTbl = isSky ? this.chunking$visTopTable : null;

                if (isSky) {
                    int lowestY = updSky.chunking$getCurrentLowestY();
                    visSky.chunking$setCurrentLowestY(lowestY);
                    visTop.defaultReturnValue(lowestY);
                    this.chunking$visLowestY = lowestY;
                    visTopTbl.setDefaultReturnValue(lowestY);
                }

                if (hasChangedSections) {
                    LongIterator it = this.changedSections.iterator();
                    while (it.hasNext()) {
                        long pos = it.nextLong();
                        DataLayer dl = updMap.get(pos);
                        if (dl != null) {
                            visMap.put(pos, dl);
                            visObj = visObj.put(pos, dl);
                        } else {
                            visMap.remove(pos);
                            visObj.remove(pos);
                        }
                        if (isSky) {
                            long col = SectionPos.getZeroNode(pos);
                            if (updTop.containsKey(col)) {
                                int topVal = updTop.get(col);
                                visTop.put(col, topVal);
                                visTopTbl = visTopTbl.put(col, topVal);
                            } else {
                                visTop.remove(col);
                                visTopTbl.remove(col);
                            }
                        }
                    }
                    this.changedSections.clear();
                }

                if (isSky && hasDirtyCols) {
                    LongIterator colIt = dirtyCols.iterator();
                    while (colIt.hasNext()) {
                        long col = colIt.nextLong();
                        if (updTop.containsKey(col)) {
                            int topVal = updTop.get(col);
                            visTop.put(col, topVal);
                            visTopTbl = visTopTbl.put(col, topVal);
                        } else {
                            visTop.remove(col);
                            visTopTbl.remove(col);
                        }
                    }
                    dirtyCols.clear();
                } else if (hasDirtyCols) {
                    dirtyCols.clear();
                }

                this.chunking$visObjTable = visObj;
                if (isSky) {
                    this.chunking$visTopTable = visTopTbl;
                }
            } finally {
                this.chunking$visibleLock.unlockWrite(ws);
            }
        }

        if (!this.sectionsAffectedByLightUpdates.isEmpty()) {
            LongIterator affectedIt = this.sectionsAffectedByLightUpdates.iterator();
            while (affectedIt.hasNext()) {
                long sectionPos = affectedIt.nextLong();
                this.chunkSource.onLightUpdate(this.layer, SectionPos.of(sectionPos));
            }
            this.sectionsAffectedByLightUpdates.clear();
        }
    }
}
