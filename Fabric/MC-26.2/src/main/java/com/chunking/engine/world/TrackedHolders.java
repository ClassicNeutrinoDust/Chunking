package com.chunking.engine.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

/**
 * Delta-tracking {@link Long2ObjectLinkedOpenHashMap} backing {@code ChunkMap.updatingChunkMap}.
 *
 * <p>Records only keys inserted, overwritten, or removed since the last {@code promoteChunkMap()}
 * so {@link #flushTo} updates {@code visibleChunkMap} and {@link HolderTable}
 * incrementally with zero per-tick map cloning, shrinking backing arrays back to {@code 256}
 * once drained.
 */
public final class TrackedHolders<V> extends Long2ObjectLinkedOpenHashMap<V> {
    private static final int INITIAL_CAPACITY = 256;
    private static final Object REMOVED = new Object();

    private final Long2ObjectOpenHashMap<Object> pendingDeltas = new Long2ObjectOpenHashMap<>(64);
    private boolean fullSyncRequired = false;
    private long[] deltaKeys = new long[128];
    private Object[] deltaVals = new Object[128];

    public TrackedHolders(int expected) {
        super(Math.max(INITIAL_CAPACITY, expected));
    }

    private void recordPut(long k, V v) {
        this.pendingDeltas.put(k, v != null ? v : REMOVED);
    }

    @Override
    public V put(long k, V v) {
        V prev = super.put(k, v);
        recordPut(k, v);
        return prev;
    }

    @Override
    public V putAndMoveToFirst(long k, V v) {
        V prev = super.putAndMoveToFirst(k, v);
        recordPut(k, v);
        return prev;
    }

    @Override
    public V putAndMoveToLast(long k, V v) {
        V prev = super.putAndMoveToLast(k, v);
        recordPut(k, v);
        return prev;
    }

    @Override
    public void putAll(Map<? extends Long, ? extends V> m) {
        super.putAll(m);
        for (Map.Entry<? extends Long, ? extends V> e : m.entrySet()) {
            Long k = e.getKey();
            if (k != null) {
                recordPut(k.longValue(), e.getValue());
            }
        }
    }

    @Override
    public V putIfAbsent(long k, V v) {
        int before = this.size;
        V res = super.putIfAbsent(k, v);
        if (this.size != before) {
            recordPut(k, v);
        }
        return res;
    }

    @Override
    public V remove(long k) {
        int before = this.size;
        V prev = super.remove(k);
        if (this.size != before) {
            this.pendingDeltas.put(k, REMOVED);
        }
        return prev;
    }

    @Override
    public boolean remove(long k, Object value) {
        boolean removed = super.remove(k, value);
        if (removed) {
            this.pendingDeltas.put(k, REMOVED);
        }
        return removed;
    }

    @Override
    public V removeFirst() {
        if (!isEmpty()) {
            long k = firstLongKey();
            V prev = super.removeFirst();
            this.pendingDeltas.put(k, REMOVED);
            return prev;
        }
        return super.removeFirst();
    }

    @Override
    public V removeLast() {
        if (!isEmpty()) {
            long k = lastLongKey();
            V prev = super.removeLast();
            this.pendingDeltas.put(k, REMOVED);
            return prev;
        }
        return super.removeLast();
    }

    @Override
    public boolean replace(long k, V oldValue, V newValue) {
        boolean replaced = super.replace(k, oldValue, newValue);
        if (replaced) {
            recordPut(k, newValue);
        }
        return replaced;
    }

    @Override
    public V replace(long k, V value) {
        boolean present = super.containsKey(k);
        V prev = super.replace(k, value);
        if (present) {
            recordPut(k, value);
        }
        return prev;
    }

    @Override
    public V computeIfAbsent(long k, LongFunction<? extends V> mappingFunction) {
        int before = this.size;
        V res = super.computeIfAbsent(k, mappingFunction);
        if (this.size != before && super.containsKey(k)) {
            recordPut(k, res);
        }
        return res;
    }

    @Override
    public V computeIfAbsent(long k, Long2ObjectFunction<? extends V> mappingFunction) {
        int before = this.size;
        V res = super.computeIfAbsent(k, mappingFunction);
        if (this.size != before && super.containsKey(k)) {
            recordPut(k, res);
        }
        return res;
    }

    @Override
    public V computeIfPresent(long k, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
        boolean had = super.containsKey(k);
        V res = super.computeIfPresent(k, remappingFunction);
        if (had) {
            this.pendingDeltas.put(k, super.containsKey(k) && res != null ? res : REMOVED);
        }
        return res;
    }

    @Override
    public V compute(long k, BiFunction<? super Long, ? super V, ? extends V> remappingFunction) {
        boolean had = super.containsKey(k);
        V res = super.compute(k, remappingFunction);
        if (super.containsKey(k)) {
            recordPut(k, res);
        } else if (had) {
            this.pendingDeltas.put(k, REMOVED);
        }
        return res;
    }

    @Override
    public V merge(long k, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        boolean had = super.containsKey(k);
        V res = super.merge(k, value, remappingFunction);
        if (super.containsKey(k)) {
            recordPut(k, res);
        } else if (had) {
            this.pendingDeltas.put(k, REMOVED);
        }
        return res;
    }

    @Override
    public void clear() {
        super.clear();
        this.pendingDeltas.clear();
        this.fullSyncRequired = true;
    }

    @Override
    public it.unimi.dsi.fastutil.objects.ObjectCollection<V> values() {
        it.unimi.dsi.fastutil.objects.ObjectCollection<V> base = super.values();
        return new it.unimi.dsi.fastutil.objects.AbstractObjectCollection<>() {
            @Override
            public it.unimi.dsi.fastutil.objects.ObjectIterator<V> iterator() {
                final it.unimi.dsi.fastutil.objects.ObjectIterator<V> it = base.iterator();
                return new it.unimi.dsi.fastutil.objects.ObjectIterator<>() {
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public V next() { return it.next(); }
                    @Override public void remove() {
                        it.remove();
                        TrackedHolders.this.fullSyncRequired = true;
                    }
                };
            }
            @Override public int size() { return base.size(); }
            @Override public void clear() { TrackedHolders.this.clear(); }
        };
    }

    @SuppressWarnings("unchecked")
    public void flushTo(
            Long2ObjectLinkedOpenHashMap<V> visibleMap,
            HolderTable<V> concurrentVisible
    ) {
        if (this.fullSyncRequired) {
            visibleMap.clear();
            if (!this.isEmpty()) {
                visibleMap.putAll(this);
            } else {
                visibleMap.trim(INITIAL_CAPACITY);
                this.trim(INITIAL_CAPACITY);
            }
            concurrentVisible.resetFrom(this);
            this.pendingDeltas.clear();
            this.fullSyncRequired = false;
            return;
        }
        int deltaCount = this.pendingDeltas.size();
        if (deltaCount == 0) {
            return;
        }
        if (deltaCount > this.deltaKeys.length) {
            int newCap = Math.max(deltaCount, this.deltaKeys.length << 1);
            this.deltaKeys = new long[newCap];
            this.deltaVals = new Object[newCap];
        }
        long[] dKeys = this.deltaKeys;
        Object[] dVals = this.deltaVals;
        int idx = 0;
        var it = this.pendingDeltas.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<Object> entry = it.next();
            long k = entry.getLongKey();
            Object v = entry.getValue();
            dKeys[idx] = k;
            dVals[idx] = v;
            idx++;
            if (v == REMOVED) {
                visibleMap.remove(k);
            } else {
                visibleMap.put(k, (V) v);
            }
        }
        concurrentVisible.applyBatch(dKeys, dVals, idx, REMOVED);
        Arrays.fill(dVals, 0, idx, null);
        this.pendingDeltas.clear();
        if (deltaCount > 1024) {
            this.pendingDeltas.trim(INITIAL_CAPACITY);
        }
        if (this.isEmpty() && visibleMap.isEmpty()) {
            visibleMap.trim(INITIAL_CAPACITY);
            this.trim(INITIAL_CAPACITY);
        }
    }
}
