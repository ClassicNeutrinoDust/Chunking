package com.chunking.engine.io;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Bounded LRU cache of pre-encoded deflate region frames ({@code [4B len][1B version=2][zlib]}).
 * Each entry is a compact ~4-6 KB {@code byte[]} with zero GC reference graph traversal cost,
 * enabling 16,384 cached perimeter chunk frames in ~75 MB on dedicated servers while dynamically
 * capping retention on active 4 GB client heaps to prevent Old Gen pressure.
 *
 * <p>Supports both thread-safe (default) and unsynchronized shard-local modes so single-thread
 * shard-owned instances skip redundant monitor acquisition.
 */
public final class FrameCache {
    private static final int CLIENT_MAX_ENTRIES_CAP = 2048;

    private final int maxEntries;
    private final boolean threadSafe;
    private final Long2ObjectLinkedOpenHashMap<byte[]> map;
    private int effectiveMaxEntries;
    private int clientCheckCounter;

    public FrameCache(int maxEntries) {
        this(maxEntries, true);
    }

    public FrameCache(int maxEntries, boolean threadSafe) {
        this.maxEntries = Math.max(0, maxEntries);
        this.effectiveMaxEntries = this.maxEntries;
        this.threadSafe = threadSafe;
        this.map = this.maxEntries > 0
                ? new Long2ObjectLinkedOpenHashMap<>(Math.min(1024, this.maxEntries), 0.75F)
                : null;
    }

    public static FrameCache createUnsynchronized(int maxEntries) {
        return new FrameCache(maxEntries, false);
    }

    private int currentCap() {
        if ((++this.clientCheckCounter & 63) == 0) {
            boolean clientActive = com.chunking.engine.scheduler.FrameGuard.get().hasActiveClientFrames();
            this.effectiveMaxEntries = clientActive
                    ? Math.min(this.maxEntries, CLIENT_MAX_ENTRIES_CAP)
                    : this.maxEntries;
        }
        return this.effectiveMaxEntries;
    }

    public byte[] get(long packedPos) {
        if (this.map == null) {
            return null;
        }
        if (!this.threadSafe) {
            return this.map.getAndMoveToFirst(packedPos);
        }
        synchronized (this) {
            return this.map.getAndMoveToFirst(packedPos);
        }
    }

    public void put(long packedPos, byte[] frame) {
        if (this.map == null || frame == null || frame.length <= 5) {
            return;
        }
        if (!this.threadSafe) {
            this.map.putAndMoveToFirst(packedPos, frame);
            int cap = currentCap();
            while (this.map.size() > cap) {
                this.map.removeLast();
            }
            return;
        }
        synchronized (this) {
            this.map.putAndMoveToFirst(packedPos, frame);
            int cap = currentCap();
            while (this.map.size() > cap) {
                this.map.removeLast();
            }
        }
    }

    public void remove(long packedPos) {
        if (this.map == null) {
            return;
        }
        if (!this.threadSafe) {
            this.map.remove(packedPos);
            return;
        }
        synchronized (this) {
            this.map.remove(packedPos);
        }
    }

    public void clear() {
        if (this.map == null) {
            return;
        }
        if (!this.threadSafe) {
            this.map.clear();
            return;
        }
        synchronized (this) {
            this.map.clear();
        }
    }

    public int size() {
        if (this.map == null) {
            return 0;
        }
        if (!this.threadSafe) {
            return this.map.size();
        }
        synchronized (this) {
            return this.map.size();
        }
    }
}
