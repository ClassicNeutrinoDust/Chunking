package com.chunking.engine.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 16-stripe primitive {@code long} set used by {@code ChunkTaskDispatcher} to track active chunk
 * keys without boxing {@code Long} objects on every {@code submit}/{@code onLevelChange}/{@code popTasks}.
 */
public final class FastLongSet {
    private static final int STRIPE_COUNT = 16;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;

    private final Object[] locks = new Object[STRIPE_COUNT];
    private final LongOpenHashSet[] sets = new LongOpenHashSet[STRIPE_COUNT];

    public FastLongSet(int expectedPerStripe) {
        int cap = Math.max(16, expectedPerStripe);
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.locks[i] = new Object();
            this.sets[i] = new LongOpenHashSet(cap);
        }
    }

    private static int stripeIndex(long key) {
        return ((int) HashCommon.mix(key)) & STRIPE_MASK;
    }

    public void add(long key) {
        int idx = stripeIndex(key);
        synchronized (this.locks[idx]) {
            this.sets[idx].add(key);
        }
    }

    public boolean remove(long key) {
        int idx = stripeIndex(key);
        synchronized (this.locks[idx]) {
            return this.sets[idx].remove(key);
        }
    }

    public boolean contains(long key) {
        int idx = stripeIndex(key);
        synchronized (this.locks[idx]) {
            return this.sets[idx].contains(key);
        }
    }

    public void clear() {
        for (int i = 0; i < STRIPE_COUNT; i++) {
            synchronized (this.locks[i]) {
                this.sets[i].clear();
            }
        }
    }
}
