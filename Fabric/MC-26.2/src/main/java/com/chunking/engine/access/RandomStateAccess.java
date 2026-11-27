package com.chunking.engine.access;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Shared RandomState caches for preliminary surface levels, border noise slices, and base heights.
 */
public interface RandomStateAccess {
    int BORDER_SLICE_CACHE_SIZE = 8192;
    int BORDER_SLICE_CACHE_MASK = BORDER_SLICE_CACHE_SIZE - 1;

    int BASE_HEIGHT_CACHE_SIZE = 16384;
    int BASE_HEIGHT_CACHE_MASK = BASE_HEIGHT_CACHE_SIZE - 1;
    int BASE_HEIGHT_MISS = Integer.MIN_VALUE;

    record BorderSliceEntry(long key, int count, int minYCell, int cellHeight, double[] values) {}

    record BaseHeightEntry(int x, int z, int typeOrd, int minY, int height, int value) {}

    AtomicLongArray chunking$prelimSurfaceCache();

    default AtomicReferenceArray<BorderSliceEntry> chunking$borderSliceCache() {
        return null;
    }

    default AtomicReferenceArray<BaseHeightEntry> chunking$baseHeightCache() {
        return null;
    }

    static int baseHeightSlot(int x, int z, int typeOrd, int minY, int height) {
        int h = x * 73856093 ^ z * 19349663 ^ typeOrd * 83492791
                ^ minY * 0x9E3779B9 ^ height * 0x85EBCA6B;
        h ^= (h >>> 16);
        return h & BASE_HEIGHT_CACHE_MASK;
    }

    static int lookupBaseHeight(
            AtomicReferenceArray<BaseHeightEntry> cache,
            int x,
            int z,
            int typeOrd,
            int minY,
            int height
    ) {
        if (cache == null) {
            return BASE_HEIGHT_MISS;
        }
        BaseHeightEntry e = cache.getAcquire(baseHeightSlot(x, z, typeOrd, minY, height));
        if (e != null && e.x == x && e.z == z && e.typeOrd == typeOrd
                && e.minY == minY && e.height == height) {
            return e.value;
        }
        return BASE_HEIGHT_MISS;
    }

    static void storeBaseHeight(
            AtomicReferenceArray<BaseHeightEntry> cache,
            int x,
            int z,
            int typeOrd,
            int minY,
            int height,
            int value
    ) {
        if (cache == null || value == BASE_HEIGHT_MISS) {
            return;
        }
        cache.setRelease(
                baseHeightSlot(x, z, typeOrd, minY, height),
                new BaseHeightEntry(x, z, typeOrd, minY, height, value)
        );
    }
}
