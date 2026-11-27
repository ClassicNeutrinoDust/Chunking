package com.chunking.engine.io;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Non-evicting 2-bit-per-chunk status table (unknown / proto / full) backed by 256 bytes per 32x32 region.
 */
public final class RegionKinds {
    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_PROTO = 1;
    public static final int STATE_FULL = 2;

    private static final int LONGS_PER_REGION = 32;

    private final ConcurrentHashMap<Long, AtomicLongArray> regions;

    public RegionKinds(int expectedRegions) {
        this.regions = new ConcurrentHashMap<>(Math.max(16, expectedRegions));
    }

    private static long regionKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
    }

    private static int chunkIndex(int cx, int cz) {
        return ((cz & 31) << 5) | (cx & 31);
    }

    public int get(long packedChunkPos) {
        return get(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos));
    }

    public int get(int cx, int cz) {
        AtomicLongArray arr = this.regions.get(Long.valueOf(regionKey(cx >> 5, cz >> 5)));
        if (arr == null) {
            return STATE_UNKNOWN;
        }
        int idx = chunkIndex(cx, cz);
        long word = arr.get(idx >>> 5);
        int shift = (idx & 31) << 1;
        return (int) ((word >>> shift) & 3L);
    }

    public void setProto(long packedChunkPos) {
        setState(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos), STATE_PROTO);
    }

    public void setFull(long packedChunkPos) {
        setState(ChunkPos.getX(packedChunkPos), ChunkPos.getZ(packedChunkPos), STATE_FULL);
    }

    public void setState(int cx, int cz, int state) {
        int clamped = state & 3;
        Long key = Long.valueOf(regionKey(cx >> 5, cz >> 5));
        AtomicLongArray arr = this.regions.get(key);
        if (arr == null) {
            arr = this.regions.computeIfAbsent(key, k -> new AtomicLongArray(LONGS_PER_REGION));
        }
        int idx = chunkIndex(cx, cz);
        int wordIdx = idx >>> 5;
        int shift = (idx & 31) << 1;
        long clearMask = ~(3L << shift);
        long setBits = ((long) clamped) << shift;
        while (true) {
            long cur = arr.get(wordIdx);
            long next = (cur & clearMask) | setBits;
            if (cur == next || arr.compareAndSet(wordIdx, cur, next)) {
                return;
            }
        }
    }

    public void clear() {
        this.regions.clear();
    }
}
