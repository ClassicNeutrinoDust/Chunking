package com.chunking.engine.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.util.concurrent.locks.StampedLock;

/**
 * Single-writer, optimistic lock-free multi-reader hash table mirroring {@code ChunkMap.visibleChunkMap}.
 *
 * <p>Updated in place under a single {@link StampedLock#writeLock()} per delta batch inside
 * {@code ChunkMap.promoteChunkMap()}, eliminating the ~15 GB/min full-map {@code clone()} allocation
 * churn on sustained worldgen sweeps while keeping off-thread {@code getVisibleChunkIfPresent} lookups
 * lock-free via {@link StampedLock#tryOptimisticRead()}.
 */
public final class HolderTable<T> {
    private static final int INITIAL_CAPACITY = 256;

    private static final class Table<T> {
        final long[] keys;
        final Object[] vals;
        final int mask;
        final int maxFill;
        volatile boolean hasZero;
        volatile T zeroVal;
        int size;

        Table(int expected) {
            int cap = HashCommon.nextPowerOfTwo(Math.max(16, expected));
            this.keys = new long[cap];
            this.vals = new Object[cap];
            this.mask = cap - 1;
            this.maxFill = (cap * 7) / 10;
        }

        int totalSize() {
            return this.size + (this.hasZero ? 1 : 0);
        }

        @SuppressWarnings("unchecked")
        T getOptimistic(long key) {
            if (key == 0L) {
                return this.hasZero ? this.zeroVal : null;
            }
            long[] ks = this.keys;
            Object[] vs = this.vals;
            int m = this.mask;
            int idx = ((int) HashCommon.mix(key)) & m;
            for (int steps = 0; steps <= m; steps++) {
                long k = ks[idx];
                if (k == key) {
                    return (T) vs[idx];
                }
                if (k == 0L) {
                    return null;
                }
                idx = (idx + 1) & m;
            }
            return null;
        }

        Table<T> put(long key, T val) {
            if (key == 0L) {
                this.zeroVal = val;
                this.hasZero = true;
                return this;
            }
            long[] ks = this.keys;
            Object[] vs = this.vals;
            int m = this.mask;
            int idx = ((int) HashCommon.mix(key)) & m;
            while (true) {
                long k = ks[idx];
                if (k == key) {
                    vs[idx] = val;
                    return this;
                }
                if (k == 0L) {
                    if (this.size >= this.maxFill) {
                        return rehashAndPut(key, val);
                    }
                    vs[idx] = val;
                    ks[idx] = key;
                    this.size++;
                    return this;
                }
                idx = (idx + 1) & m;
            }
        }

        void remove(long key) {
            if (key == 0L) {
                this.hasZero = false;
                this.zeroVal = null;
                return;
            }
            long[] ks = this.keys;
            Object[] vs = this.vals;
            int m = this.mask;
            int pos = ((int) HashCommon.mix(key)) & m;
            while (true) {
                long k = ks[pos];
                if (k == key) {
                    break;
                }
                if (k == 0L) {
                    return;
                }
                pos = (pos + 1) & m;
            }
            this.size--;
            while (true) {
                int last = pos;
                pos = (pos + 1) & m;
                long curr;
                while (true) {
                    curr = ks[pos];
                    if (curr == 0L) {
                        ks[last] = 0L;
                        vs[last] = null;
                        return;
                    }
                    int slot = ((int) HashCommon.mix(curr)) & m;
                    if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) {
                        break;
                    }
                    pos = (pos + 1) & m;
                }
                vs[last] = vs[pos];
                ks[last] = curr;
            }
        }

        @SuppressWarnings("unchecked")
        private Table<T> rehashAndPut(long newKey, T newVal) {
            Table<T> next = new Table<>(this.keys.length << 1);
            if (this.hasZero) {
                next.zeroVal = this.zeroVal;
                next.hasZero = true;
            }
            long[] ks = this.keys;
            Object[] vs = this.vals;
            for (int i = 0; i < ks.length; i++) {
                long k = ks[i];
                if (k != 0L) {
                    next.insertFresh(k, (T) vs[i]);
                }
            }
            next.insertFresh(newKey, newVal);
            return next;
        }

        private void insertFresh(long key, T val) {
            int idx = ((int) HashCommon.mix(key)) & this.mask;
            while (this.keys[idx] != 0L) {
                idx = (idx + 1) & this.mask;
            }
            this.vals[idx] = val;
            this.keys[idx] = key;
            this.size++;
        }
    }

    private final StampedLock lock = new StampedLock();
    private volatile Table<T> table;
    private volatile int cachedSize;

    public HolderTable(int expected) {
        this.table = new Table<>(Math.max(INITIAL_CAPACITY, expected));
    }

    public int size() {
        return this.cachedSize;
    }

    public T get(long key) {
        long stamp = this.lock.tryOptimisticRead();
        Table<T> t = this.table;
        T val = t != null ? t.getOptimistic(key) : null;
        if (this.lock.validate(stamp)) {
            return val;
        }
        stamp = this.lock.tryOptimisticRead();
        t = this.table;
        val = t != null ? t.getOptimistic(key) : null;
        if (this.lock.validate(stamp)) {
            return val;
        }
        long readStamp = this.lock.readLock();
        try {
            t = this.table;
            return t != null ? t.getOptimistic(key) : null;
        } finally {
            this.lock.unlockRead(readStamp);
        }
    }

    @SuppressWarnings("unchecked")
    public void applyBatch(long[] keys, Object[] vals, int count, Object removedSentinel) {
        long stamp = this.lock.writeLock();
        try {
            Table<T> t = this.table;
            for (int i = 0; i < count; i++) {
                long k = keys[i];
                Object v = vals[i];
                if (v == removedSentinel || v == null) {
                    t.remove(k);
                } else {
                    t = t.put(k, (T) v);
                }
            }
            if (t.totalSize() == 0 && t.keys.length > INITIAL_CAPACITY * 2) {
                t = new Table<>(INITIAL_CAPACITY);
            }
            this.table = t;
            this.cachedSize = t.totalSize();
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public void resetFrom(Long2ObjectLinkedOpenHashMap<T> source) {
        long stamp = this.lock.writeLock();
        try {
            int expected = Math.max(INITIAL_CAPACITY, (source.size() * 3) / 2);
            Table<T> next = new Table<>(expected);
            var it = source.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<T> entry = it.next();
                T val = entry.getValue();
                if (val != null) {
                    next = next.put(entry.getLongKey(), val);
                }
            }
            this.table = next;
            this.cachedSize = next.totalSize();
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }
}
