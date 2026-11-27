package com.chunking.engine.mixin;

import com.chunking.engine.io.RegionMask;
import com.chunking.engine.mixin.accessor.SimpleRegionStorageAccessor;
import com.chunking.engine.access.PoiLoadedChunksAccess;
import com.chunking.engine.access.RegionStorageAccess;
import com.chunking.engine.access.SectionStorageEvictAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Fast-paths {@link SectionStorage#tryRead(ChunkPos)} in O(1) time via a compact region bitset
 * (~128 bytes/region, zero boxed {@code Long} keys) when no POI data has been saved, and evicts
 * unloaded section caches on {@code ChunkMap} unload to keep memory O(loaded) over 1M+ chunks.
 */
@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin implements SectionStorageEvictAccess {
    @Unique
    private static final CompletableFuture<Optional<?>> CHUNKING$EMPTY_OPTIONAL_FUTURE =
            CompletableFuture.completedFuture(Optional.empty());

    @Unique
    private static final int CHUNKING$POI_RING_CAP = 49_152;

    @Unique
    private static final boolean CHUNKING$LITHIUM_LOADED = MixinPlugin.isLithiumLoaded();

    @Shadow
    @Final
    private SimpleRegionStorage simpleRegionStorage;

    @Shadow
    @Final
    private Long2ObjectMap<?> storage;

    @Shadow
    @Final
    private LongLinkedOpenHashSet dirtyChunks;

    @Shadow
    @Final
    private LongSet loadedChunks;

    @Shadow
    @Final
    private Long2ObjectMap<?> pendingLoads;

    @Shadow
    @Final
    private Object loadLock;

    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    @Unique
    private volatile RegionMask chunking$writtenChunks;

    // Initialized in <init> (mixin field)
    @Unique
    private LongOpenHashSet chunking$pendingEvictAfterFlush;

    @Unique
    private LongOpenHashSet chunking$chunksWithStorage;

    @Unique
    private LongOpenHashSet chunking$pendingEvictSet() {
        LongOpenHashSet s = this.chunking$pendingEvictAfterFlush;
        if (s == null) {
            s = new LongOpenHashSet(64);
            this.chunking$pendingEvictAfterFlush = s;
        }
        return s;
    }

    @Unique
    private LongOpenHashSet chunking$chunksWithStorageSet() {
        LongOpenHashSet s = this.chunking$chunksWithStorage;
        if (s == null) {
            s = new LongOpenHashSet(256);
            this.chunking$chunksWithStorage = s;
        }
        return s;
    }

    @Unique
    private long[] chunking$poiEvictRing;

    @Unique
    private int chunking$poiEvictHead;

    @Unique
    private boolean chunking$poiEvictFull;

    @Unique
    private RegionMask chunking$writtenChunksSet() {
        RegionMask s = this.chunking$writtenChunks;
        if (s == null) {
            synchronized (this) {
                s = this.chunking$writtenChunks;
                if (s == null) {
                    s = new RegionMask(64);
                    this.chunking$writtenChunks = s;
                }
            }
        }
        return s;
    }

    @Inject(method = "writeChunk", at = @At("HEAD"))
    private void chunking$recordWrittenPoiChunk(ChunkPos pos, CallbackInfo ci) {
        this.chunking$writtenChunksSet().add(pos.x(), pos.z());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "tryRead", at = @At("HEAD"), cancellable = true)
    private void chunking$fastTryReadOnNewRegion(
            ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<?>>> cir
    ) {
        RegionMask s = this.chunking$writtenChunks;
        if ((s == null || !s.contains(pos.x(), pos.z()))
                && ((SimpleRegionStorageAccessor) this.simpleRegionStorage).chunking$getWorker()
                instanceof RegionStorageAccess access
                && !access.chunking$hasPreExistingRegionFile(pos.getRegionX(), pos.getRegionZ())) {
            cir.setReturnValue((CompletableFuture) CHUNKING$EMPTY_OPTIONAL_FUTURE);
        }
    }

    @Override
    public void chunking$evictUnloadedChunk(long packedChunkPos) {
        if (CHUNKING$LITHIUM_LOADED) {
            return;
        }
        long[] ring = this.chunking$poiEvictRing;
        if (ring == null) {
            ring = new long[CHUNKING$POI_RING_CAP];
            this.chunking$poiEvictRing = ring;
        }
        if (this.chunking$reloadedSinceEvict != null) {
            synchronized (this.loadLock) {
                this.chunking$reloadedSinceEvict.remove(packedChunkPos);
            }
        }
        int idx = this.chunking$poiEvictHead;
        boolean wasFull = this.chunking$poiEvictFull;
        long expired = ring[idx];
        ring[idx] = packedChunkPos;
        idx++;
        if (idx >= CHUNKING$POI_RING_CAP) {
            idx = 0;
            this.chunking$poiEvictFull = true;
        }
        this.chunking$poiEvictHead = idx;
        if (!wasFull || expired == packedChunkPos) {
            return;
        }
        if (this.dirtyChunks.contains(expired)) {
            this.chunking$pendingEvictSet().add(expired);
            return;
        }
        if (this.chunking$pendingEvictAfterFlush != null) {
            this.chunking$pendingEvictAfterFlush.remove(expired);
        }
        boolean evicted = false;
        synchronized (this.loadLock) {
            if (!this.pendingLoads.containsKey(expired)
                    && (this.chunking$reloadedSinceEvict == null || !this.chunking$reloadedSinceEvict.remove(expired))) {
                chunking$evictSectionsAndLoadedLocked(expired);
                evicted = true;
            }
        }
        if (evicted && (Object) this instanceof PoiLoadedChunksAccess poi) {
            poi.chunking$removeLoadedChunk(expired);
        }
    }

    @Shadow
    protected abstract boolean outsideStoredRange(long sectionPos);

    @Unique
    private LongOpenHashSet chunking$reloadedSinceEvict;

    @Unique
    private void chunking$markReloadedIfRingActive(long chunkKey) {
        if (CHUNKING$LITHIUM_LOADED || this.chunking$poiEvictRing == null) {
            return;
        }
        synchronized (this.loadLock) {
            if (this.chunking$reloadedSinceEvict == null) {
                this.chunking$reloadedSinceEvict = new LongOpenHashSet(256);
            }
            this.chunking$reloadedSinceEvict.add(chunkKey);
        }
    }

    @Inject(method = "prefetch(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void chunking$markPrefetchReloaded(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (pos != null) {
            chunking$markReloadedIfRingActive(pos.pack());
        }
    }

    @Inject(method = "getOrLoad(J)Ljava/util/Optional;", at = @At("HEAD"))
    private void chunking$markGetOrLoadReloaded(long sectionPos, CallbackInfoReturnable<Optional<?>> cir) {
        chunking$markReloadedIfRingActive(ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
    }

    @Inject(
            method = "unpackChunk(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/storage/SectionStorage$PackedChunk;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$skipEmptyUnpackSections(ChunkPos pos, @Coerce Object packed, CallbackInfo ci) {
        if (CHUNKING$LITHIUM_LOADED) {
            return;
        }
        long key = pos.pack();
        if (this.chunking$poiEvictRing != null) {
            synchronized (this.loadLock) {
                if (this.chunking$reloadedSinceEvict == null) {
                    this.chunking$reloadedSinceEvict = new LongOpenHashSet(256);
                }
                this.chunking$reloadedSinceEvict.add(key);
            }
        }
        if (packed == null) {
            ci.cancel();
            return;
        }
        this.chunking$chunksWithStorageSet().add(key);
    }

    @Inject(method = "getOrCreate(J)Ljava/lang/Object;", at = @At("RETURN"))
    private void chunking$recordChunkWithStorage(long sectionPos, CallbackInfoReturnable<Object> cir) {
        if (CHUNKING$LITHIUM_LOADED) {
            return;
        }
        this.chunking$chunksWithStorageSet().add(ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
    }

    @Inject(method = "get(J)Ljava/util/Optional;", at = @At("RETURN"), cancellable = true)
    private void chunking$implicitEmptyForLoadedChunk(long sectionPos, CallbackInfoReturnable<Optional<?>> cir) {
        if (cir.getReturnValue() != null || this.outsideStoredRange(sectionPos)) {
            return;
        }
        long chunkKey = ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos));
        boolean loaded;
        synchronized (this.loadLock) {
            loaded = this.loadedChunks.contains(chunkKey) && !this.pendingLoads.containsKey(chunkKey);
        }
        if (loaded) {
            cir.setReturnValue(Optional.empty());
        }
    }

    @Unique
    private void chunking$evictSectionsAndLoadedLocked(long packedChunkPos) {
        if (this.chunking$chunksWithStorage != null && this.chunking$chunksWithStorage.remove(packedChunkPos)) {
            int cx = ChunkPos.getX(packedChunkPos);
            int cz = ChunkPos.getZ(packedChunkPos);
            int minSy = this.levelHeightAccessor.getMinSectionY();
            int maxSy = this.levelHeightAccessor.getMaxSectionY();
            for (int sy = minSy; sy <= maxSy; sy++) {
                this.storage.remove(SectionPos.asLong(cx, sy, cz));
            }
        }
        this.loadedChunks.remove(packedChunkPos);
    }

    @Inject(method = "writeChunk(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("RETURN"))
    private void chunking$evictPendingAfterWriteFlush(ChunkPos pos, CallbackInfo ci) {
        LongOpenHashSet pendingEvict = this.chunking$pendingEvictAfterFlush;
        if (CHUNKING$LITHIUM_LOADED || pendingEvict == null || pendingEvict.isEmpty()) {
            return;
        }
        long packedChunkPos = pos.pack();
        if (!pendingEvict.remove(packedChunkPos)) {
            return;
        }
        if (this.dirtyChunks.contains(packedChunkPos)) {
            pendingEvict.add(packedChunkPos);
            return;
        }
        boolean evicted = false;
        synchronized (this.loadLock) {
            if (!this.pendingLoads.containsKey(packedChunkPos)
                    && (this.chunking$reloadedSinceEvict == null || !this.chunking$reloadedSinceEvict.remove(packedChunkPos))) {
                chunking$evictSectionsAndLoadedLocked(packedChunkPos);
                evicted = true;
            }
        }
        if (evicted && (Object) this instanceof PoiLoadedChunksAccess poi) {
            poi.chunking$removeLoadedChunk(packedChunkPos);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void chunking$clearWrittenChunksOnClose(CallbackInfo ci) {
        RegionMask s = this.chunking$writtenChunks;
        if (s != null) {
            s.clear();
        }
        this.chunking$poiEvictRing = null;
        this.chunking$poiEvictHead = 0;
        this.chunking$poiEvictFull = false;
        if (this.chunking$pendingEvictAfterFlush != null) {
            this.chunking$pendingEvictAfterFlush.clear();
        }
        if (this.chunking$reloadedSinceEvict != null) {
            this.chunking$reloadedSinceEvict.clear();
        }
        if (this.chunking$chunksWithStorage != null) {
            this.chunking$chunksWithStorage.clear();
        }
    }
}
