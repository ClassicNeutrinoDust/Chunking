package com.chunking.engine.mixin;

import com.chunking.engine.access.RandomStateAccess;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Shared non-blocking caches on RandomState for preliminary surface levels, border noise slices, and base heights.
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin implements RandomStateAccess {
    @Unique
    private volatile AtomicLongArray chunking$prelimSurfaceCache;

    @Unique
    private volatile AtomicReferenceArray<BorderSliceEntry> chunking$borderSliceCache;

    @Unique
    private volatile AtomicReferenceArray<BaseHeightEntry> chunking$baseHeightCache;

    @Override
    public AtomicLongArray chunking$prelimSurfaceCache() {
        AtomicLongArray cache = this.chunking$prelimSurfaceCache;
        if (cache == null) {
            synchronized (this) {
                cache = this.chunking$prelimSurfaceCache;
                if (cache == null) {
                    cache = new AtomicLongArray(16384);
                    this.chunking$prelimSurfaceCache = cache;
                }
            }
        }
        return cache;
    }

    @Override
    public AtomicReferenceArray<BorderSliceEntry> chunking$borderSliceCache() {
        AtomicReferenceArray<BorderSliceEntry> cache = this.chunking$borderSliceCache;
        if (cache == null) {
            synchronized (this) {
                cache = this.chunking$borderSliceCache;
                if (cache == null) {
                    cache = new AtomicReferenceArray<>(BORDER_SLICE_CACHE_SIZE);
                    this.chunking$borderSliceCache = cache;
                }
            }
        }
        return cache;
    }

    @Override
    public AtomicReferenceArray<BaseHeightEntry> chunking$baseHeightCache() {
        AtomicReferenceArray<BaseHeightEntry> cache = this.chunking$baseHeightCache;
        if (cache == null) {
            synchronized (this) {
                cache = this.chunking$baseHeightCache;
                if (cache == null) {
                    cache = new AtomicReferenceArray<>(BASE_HEIGHT_CACHE_SIZE);
                    this.chunking$baseHeightCache = cache;
                }
            }
        }
        return cache;
    }
}
