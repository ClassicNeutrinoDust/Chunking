package com.chunking.engine.world;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.AbstractLong2BooleanMap;
import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanFunction;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Striped concurrent tables backing {@code StructureCheck.loadedChunks} and
 * {@code StructureCheck.featureChecks} (kept outside {@code @Mixin} classes for SpongeMixin).
 */
public final class StructureCache {
    private StructureCache() {
    }

    public static final class StripedLoadedMap extends AbstractLong2ObjectMap<Object2IntMap<Structure>> {
        private static final int STRIPES = 32;
        private static final int MASK = STRIPES - 1;
        private static final int RING_CAP_PER_STRIPE = 1024;

        private static final class Stripe {
            final Long2ObjectOpenHashMap<Object2IntMap<Structure>> map = new Long2ObjectOpenHashMap<>(64);
            final long[] ring = new long[RING_CAP_PER_STRIPE];
            int head;
            boolean full;

            Object2IntMap<Structure> putBounded(long key, Object2IntMap<Structure> val) {
                Object2IntMap<Structure> prev = this.map.put(key, val);
                if (prev == null) {
                    int idx = this.head;
                    if (this.full) {
                        long expired = this.ring[idx];
                        if (expired != key) {
                            this.map.remove(expired);
                        }
                    }
                    this.ring[idx] = key;
                    idx++;
                    if (idx >= RING_CAP_PER_STRIPE) {
                        idx = 0;
                        this.full = true;
                    }
                    this.head = idx;
                }
                return prev;
            }
        }

        private final Stripe[] stripes = new Stripe[STRIPES];

        public StripedLoadedMap() {
            for (int i = 0; i < STRIPES; i++) {
                this.stripes[i] = new Stripe();
            }
        }

        private Stripe stripeFor(long key) {
            return this.stripes[((int) HashCommon.mix(key)) & MASK];
        }

        @Override
        public Object2IntMap<Structure> get(long key) {
            Stripe s = stripeFor(key);
            synchronized (s) {
                return s.map.get(key);
            }
        }

        @Override
        public Object2IntMap<Structure> put(long key, Object2IntMap<Structure> value) {
            Object2IntMap<Structure> safe = (value == null || value.isEmpty())
                    ? Object2IntMaps.emptyMap()
                    : value;
            Stripe s = stripeFor(key);
            synchronized (s) {
                return s.putBounded(key, safe);
            }
        }

        @Override
        public Object2IntMap<Structure> remove(long key) {
            Stripe s = stripeFor(key);
            synchronized (s) {
                return s.map.remove(key);
            }
        }

        @Override
        public boolean containsKey(long key) {
            Stripe s = stripeFor(key);
            synchronized (s) {
                return s.map.containsKey(key);
            }
        }

        public void incrementReference(long packed, Structure structure) {
            Stripe s = stripeFor(packed);
            synchronized (s) {
                Object2IntMap<Structure> prev = s.map.get(packed);
                // Copy-on-write keeps published Object2IntMap instances effectively immutable for lock-free readers.
                Object2IntOpenHashMap<Structure> next = (prev == null || prev.isEmpty())
                        ? new Object2IntOpenHashMap<>(2)
                        : new Object2IntOpenHashMap<>(prev);
                next.addTo(structure, 1);
                s.putBounded(packed, next);
            }
        }

        @Override
        public int size() {
            int total = 0;
            for (int i = 0; i < STRIPES; i++) {
                Stripe s = this.stripes[i];
                synchronized (s) {
                    total += s.map.size();
                }
            }
            return total;
        }

        @Override
        public ObjectSet<Entry<Object2IntMap<Structure>>> long2ObjectEntrySet() {
            return ObjectSets.emptySet();
        }
    }

    public static final class StripedFeatureMap extends AbstractLong2BooleanMap {
        private static final int STRIPES = 8;
        private static final int MASK = STRIPES - 1;
        private static final int CAP_PER_STRIPE = 8192;

        private static final class Stripe {
            final Long2BooleanOpenHashMap map = new Long2BooleanOpenHashMap(32);
            volatile int size;
        }

        private final Stripe[] stripes = new Stripe[STRIPES];

        public StripedFeatureMap() {
            for (int i = 0; i < STRIPES; i++) {
                this.stripes[i] = new Stripe();
            }
        }

        private Stripe stripeFor(long key) {
            return this.stripes[((int) HashCommon.mix(key)) & MASK];
        }

        public boolean computeWithoutHoldingLock(long key, Long2BooleanFunction fn) {
            Stripe s = stripeFor(key);
            if (s.size > 0) {
                synchronized (s) {
                    if (s.map.containsKey(key)) {
                        return s.map.get(key);
                    }
                }
            }
            boolean val = fn.get(key);
            synchronized (s) {
                if (s.map.size() >= CAP_PER_STRIPE) {
                    s.map.clear();
                }
                s.map.put(key, val);
                s.size = s.map.size();
            }
            return val;
        }

        @Override
        public boolean get(long key) {
            Stripe s = stripeFor(key);
            if (s.size == 0) {
                return this.defRetValue;
            }
            synchronized (s) {
                return s.map.get(key);
            }
        }

        @Override
        public boolean put(long key, boolean value) {
            Stripe s = stripeFor(key);
            synchronized (s) {
                if (s.map.size() >= CAP_PER_STRIPE) {
                    s.map.clear();
                }
                boolean prev = s.map.put(key, value);
                s.size = s.map.size();
                return prev;
            }
        }

        @Override
        public boolean remove(long key) {
            Stripe s = stripeFor(key);
            // Lock-free skip during storeFullResults when this stripe has no pending feature checks.
            if (s.size == 0) {
                return this.defRetValue;
            }
            synchronized (s) {
                boolean prev = s.map.remove(key);
                s.size = s.map.size();
                return prev;
            }
        }

        @Override
        public boolean containsKey(long key) {
            Stripe s = stripeFor(key);
            if (s.size == 0) {
                return false;
            }
            synchronized (s) {
                return s.map.containsKey(key);
            }
        }

        @Override
        public int size() {
            int total = 0;
            for (int i = 0; i < STRIPES; i++) {
                total += this.stripes[i].size;
            }
            return total;
        }

        @Override
        public ObjectSet<Entry> long2BooleanEntrySet() {
            return ObjectSets.emptySet();
        }
    }
}
