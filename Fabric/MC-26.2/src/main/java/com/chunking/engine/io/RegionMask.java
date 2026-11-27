package com.chunking.engine.io;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free, region-paged bitset tracking saved or written chunk coordinates using 128 bytes
 * per 32x32 region (~128 KB per 1,000,000 chunks) with zero per-chunk boxed {@link Long} keys.
 */
public final class RegionMask {
    private static final int LONGS_PER_REGION = 16;

    private final ConcurrentHashMap<Long, AtomicLongArray> regions;

    public RegionMask(int expectedRegions) {
        this.regions = new ConcurrentHashMap<>(Math.max(16, expectedRegions));
    }

    private static long regionKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
    }

    private static int bitIndex(int cx, int cz) {
        return ((cz & 31) << 5) | (cx & 31);
    }

    public void add(long packedChunkPos) {
        add(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos));
    }

    public void add(int cx, int cz) {
        Long key = Long.valueOf(regionKey(cx >> 5, cz >> 5));
        AtomicLongArray arr = this.regions.get(key);
        if (arr == null) {
            arr = this.regions.computeIfAbsent(key, k -> new AtomicLongArray(LONGS_PER_REGION));
        }
        int idx = bitIndex(cx, cz);
        int word = idx >>> 6;
        long bit = 1L << (idx & 63);
        long cur = arr.get(word);
        while ((cur & bit) == 0L) {
            if (arr.compareAndSet(word, cur, cur | bit)) {
                return;
            }
            cur = arr.get(word);
        }
    }

    public boolean contains(long packedChunkPos) {
        return contains(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos));
    }

    public boolean contains(int cx, int cz) {
        AtomicLongArray arr = this.regions.get(Long.valueOf(regionKey(cx >> 5, cz >> 5)));
        if (arr == null) {
            return false;
        }
        int idx = bitIndex(cx, cz);
        long word = arr.get(idx >>> 6);
        return (word & (1L << (idx & 63))) != 0L;
    }

    public void clear() {
        this.regions.clear();
    }
}
