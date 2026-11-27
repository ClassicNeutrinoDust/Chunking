package com.chunking.engine.mixin;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Replaces vanilla's 2-slot linear scan cache inside {@link DataLayerStorageMap} with a 64-slot
 * direct-mapped hash cache on {@code updatingSectionData} ({@code cacheEnabled == true}), cutting
 * {@link Long2ObjectOpenHashMap#get(long)} hash probes with zero wrapper/lambda allocations.
 */
@Mixin(DataLayerStorageMap.class)
public abstract class DataLayerStorageMapMixin {
    @Unique
    private static final int CHUNKING$CACHE_SIZE = 64;

    @Unique
    private static final int CHUNKING$CACHE_MASK = CHUNKING$CACHE_SIZE - 1;

    @Shadow
    private boolean cacheEnabled;

    @Shadow
    @Final
    protected Long2ObjectOpenHashMap<DataLayer> map;

    @Shadow
    public abstract void clearCache();

    @Unique
    private long[] chunking$cacheKeys;

    @Unique
    private DataLayer[] chunking$cacheVals;

    @Unique
    private void chunking$ensureInit() {
        if (this.chunking$cacheKeys == null) {
            long[] keys = new long[CHUNKING$CACHE_SIZE];
            Arrays.fill(keys, Long.MAX_VALUE);
            this.chunking$cacheKeys = keys;
            this.chunking$cacheVals = new DataLayer[CHUNKING$CACHE_SIZE];
        }
    }

    @Unique
    private static int chunking$slot(long pos) {
        return ((int) HashCommon.mix(pos)) & CHUNKING$CACHE_MASK;
    }

    @Inject(method = "clearCache", at = @At("HEAD"))
    private void chunking$clearFastCache(CallbackInfo ci) {
        if (this.chunking$cacheKeys != null) {
            Arrays.fill(this.chunking$cacheKeys, Long.MAX_VALUE);
            Arrays.fill(this.chunking$cacheVals, null);
        }
    }

    /**
     * @author Chunking
     * @reason 64-slot direct-mapped layer cache without @WrapMethod lambda overhead.
     */
    @Overwrite
    public DataLayer getLayer(long sectionPos) {
        if (!this.cacheEnabled) {
            return this.map.get(sectionPos);
        }
        chunking$ensureInit();
        int slot = chunking$slot(sectionPos);
        if (this.chunking$cacheKeys[slot] == sectionPos) {
            return this.chunking$cacheVals[slot];
        }
        DataLayer dl = this.map.get(sectionPos);
        if (dl != null) {
            this.chunking$cacheKeys[slot] = sectionPos;
            this.chunking$cacheVals[slot] = dl;
        }
        return dl;
    }

    @Inject(method = "setLayer", at = @At("TAIL"))
    private void chunking$updateCacheOnSet(long sectionPos, DataLayer layer, CallbackInfo ci) {
        if (this.cacheEnabled && this.chunking$cacheKeys != null) {
            int slot = chunking$slot(sectionPos);
            this.chunking$cacheKeys[slot] = sectionPos;
            this.chunking$cacheVals[slot] = layer;
        }
    }

    /**
     * @author Chunking
     * @reason Zero-alloc removeLayer with direct-mapped cache invalidation.
     */
    @Overwrite
    public DataLayer removeLayer(long sectionPos) {
        DataLayer removed = this.map.remove(sectionPos);
        if (this.cacheEnabled && this.chunking$cacheKeys != null) {
            int slot = chunking$slot(sectionPos);
            if (this.chunking$cacheKeys[slot] == sectionPos) {
                this.chunking$cacheKeys[slot] = Long.MAX_VALUE;
                this.chunking$cacheVals[slot] = null;
            }
        }
        return removed;
    }

    /**
     * @author Chunking
     * @reason Zero-alloc copyDataLayer replicating vanilla map.get().copy() while updating only the modified 64-slot cache entry.
     */
    @Overwrite
    public DataLayer copyDataLayer(long sectionPos) {
        DataLayer copied = this.map.get(sectionPos).copy();
        this.map.put(sectionPos, copied);
        if (this.cacheEnabled && this.chunking$cacheKeys != null) {
            int slot = chunking$slot(sectionPos);
            this.chunking$cacheKeys[slot] = sectionPos;
            this.chunking$cacheVals[slot] = copied;
        }
        return copied;
    }
}
