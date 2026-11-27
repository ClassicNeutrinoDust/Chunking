package com.chunking.engine.world;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.world.level.chunk.DataLayer;

import java.lang.invoke.VarHandle;

/**
 * Single-writer, optimistic-read primitive-key hash tables for visible lighting snapshots.
 *
 * <p>All mutations occur strictly under {@link java.util.concurrent.locks.StampedLock#writeLock()} inside
 * {@code LayerLightSectionStorage.swapSectionMap()}, while client meshing threads and the Render thread
 * read lock-free via {@link java.util.concurrent.locks.StampedLock#tryOptimisticRead()}. Because each
 * immutable backing {@code State} holds {@code final} {@code keys}, {@code vals}, and {@code mask}
 * fields, every key publication is preceded by {@link VarHandle#releaseFence()}, and every probe loop is
 * strictly bounded by {@code mask + 1} iterations, an optimistic reader can never observe an uninitialized
 * value for a newly published key, suffer array-bounds faults, or fail to terminate under concurrent writes.
 */
public final class LightMaps {
    private static final int INITIAL_CAP = 64;

    private LightMaps() {}

    public static final class ObjTable {
        private static final class State {
            final long[] keys;
            final DataLayer[] vals;
            final int mask;
            final int maxFill;

            State(int cap) {
                this.keys = new long[cap];
                this.vals = new DataLayer[cap];
                this.mask = cap - 1;
                this.maxFill = (cap * 7) / 10;
            }
        }

        private volatile State state;
        volatile boolean hasZero;
        volatile DataLayer zeroVal;
        int size;

        public ObjTable(int expected) {
            int cap = HashCommon.nextPowerOfTwo(Math.max(16, expected));
            this.state = new State(cap);
        }

        public DataLayer getOptimistic(long key) {
            if (key == 0L) {
                if (this.hasZero) {
                    VarHandle.loadLoadFence();
                    return this.zeroVal;
                }
                return null;
            }
            State s = this.state;
            long[] ks = s.keys;
            DataLayer[] vs = s.vals;
            int m = s.mask;
            int idx = ((int) HashCommon.mix(key)) & m;
            for (int steps = 0; steps <= m; steps++) {
                long k = ks[idx];
                if (k == key) {
                    VarHandle.loadLoadFence();
                    return vs[idx];
                }
                if (k == 0L) {
                    return null;
                }
                idx = (idx + 1) & m;
            }
            return null;
        }

        public ObjTable put(long key, DataLayer val) {
            if (key == 0L) {
                this.zeroVal = val;
                VarHandle.releaseFence();
                this.hasZero = true;
                return this;
            }
            State s = this.state;
            if (this.size == 0 && !this.hasZero && s.keys.length > INITIAL_CAP) {
                s = new State(INITIAL_CAP);
                this.state = s;
            }
            long[] ks = s.keys;
            DataLayer[] vs = s.vals;
            int m = s.mask;
            int idx = ((int) HashCommon.mix(key)) & m;
            while (true) {
                long k = ks[idx];
                if (k == key) {
                    vs[idx] = val;
                    VarHandle.releaseFence();
                    return this;
                }
                if (k == 0L) {
                    if (this.size >= s.maxFill) {
                        return rehashAndPut(key, val);
                    }
                    vs[idx] = val;
                    VarHandle.releaseFence();
                    ks[idx] = key;
                    this.size++;
                    return this;
                }
                idx = (idx + 1) & m;
            }
        }

        public void remove(long key) {
            if (key == 0L) {
                this.hasZero = false;
                this.zeroVal = null;
                if (this.size == 0 && this.state.keys.length > INITIAL_CAP) {
                    this.state = new State(INITIAL_CAP);
                }
                return;
            }
            State s = this.state;
            long[] ks = s.keys;
            DataLayer[] vs = s.vals;
            int m = s.mask;
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
            if (this.size == 0 && !this.hasZero && ks.length > INITIAL_CAP) {
                this.state = new State(INITIAL_CAP);
                return;
            }
            if (ks.length > INITIAL_CAP && this.size < (ks.length >>> 3)) {
                ObjTable shrunk = new ObjTable(Math.max(INITIAL_CAP, ks.length >>> 1));
                for (int i = 0; i < ks.length; i++) {
                    long k = ks[i];
                    if (k != 0L && k != key) {
                        shrunk.insertFresh(k, vs[i]);
                    }
                }
                VarHandle.releaseFence();
                this.state = shrunk.state;
                return;
            }
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
                VarHandle.releaseFence();
                ks[last] = curr;
            }
        }

        private ObjTable rehashAndPut(long newKey, DataLayer newVal) {
            State s = this.state;
            ObjTable next = new ObjTable(s.keys.length << 1);
            if (this.hasZero) {
                next.zeroVal = this.zeroVal;
                next.hasZero = true;
            }
            long[] ks = s.keys;
            DataLayer[] vs = s.vals;
            for (int i = 0; i < ks.length; i++) {
                long k = ks[i];
                if (k != 0L) {
                    next.insertFresh(k, vs[i]);
                }
            }
            next.insertFresh(newKey, newVal);
            VarHandle.releaseFence();
            return next;
        }

        private void insertFresh(long key, DataLayer val) {
            State s = this.state;
            int idx = ((int) HashCommon.mix(key)) & s.mask;
            while (s.keys[idx] != 0L) {
                idx = (idx + 1) & s.mask;
            }
            s.vals[idx] = val;
            s.keys[idx] = key;
            this.size++;
        }
    }

    public static final class IntTable {
        private static final class State {
            final long[] keys;
            final int[] vals;
            final int mask;
            final int maxFill;

            State(int cap) {
                this.keys = new long[cap];
                this.vals = new int[cap];
                this.mask = cap - 1;
                this.maxFill = (cap * 7) / 10;
            }
        }

        private volatile State state;
        volatile boolean hasZero;
        volatile int zeroVal;
        volatile int defaultReturnValue;
        int size;

        public IntTable(int expected, int defaultReturnValue) {
            int cap = HashCommon.nextPowerOfTwo(Math.max(16, expected));
            this.state = new State(cap);
            this.defaultReturnValue = defaultReturnValue;
        }

        public void setDefaultReturnValue(int defaultReturnValue) {
            this.defaultReturnValue = defaultReturnValue;
        }

        public int getOptimistic(long key) {
            if (key == 0L) {
                if (this.hasZero) {
                    VarHandle.loadLoadFence();
                    return this.zeroVal;
                }
                return this.defaultReturnValue;
            }
            State s = this.state;
            long[] ks = s.keys;
            int[] vs = s.vals;
            int m = s.mask;
            int idx = ((int) HashCommon.mix(key)) & m;
            for (int steps = 0; steps <= m; steps++) {
                long k = ks[idx];
                if (k == key) {
                    VarHandle.loadLoadFence();
                    return vs[idx];
                }
                if (k == 0L) {
                    return this.defaultReturnValue;
                }
                idx = (idx + 1) & m;
            }
            return this.defaultReturnValue;
        }

        public IntTable put(long key, int val) {
            if (key == 0L) {
                this.zeroVal = val;
                VarHandle.releaseFence();
                this.hasZero = true;
                return this;
            }
            State s = this.state;
            if (this.size == 0 && !this.hasZero && s.keys.length > INITIAL_CAP) {
                s = new State(INITIAL_CAP);
                this.state = s;
            }
            long[] ks = s.keys;
            int[] vs = s.vals;
            int m = s.mask;
            int idx = ((int) HashCommon.mix(key)) & m;
            while (true) {
                long k = ks[idx];
                if (k == key) {
                    vs[idx] = val;
                    VarHandle.releaseFence();
                    return this;
                }
                if (k == 0L) {
                    if (this.size >= s.maxFill) {
                        return rehashAndPut(key, val);
                    }
                    vs[idx] = val;
                    VarHandle.releaseFence();
                    ks[idx] = key;
                    this.size++;
                    return this;
                }
                idx = (idx + 1) & m;
            }
        }

        public void remove(long key) {
            if (key == 0L) {
                this.hasZero = false;
                this.zeroVal = 0;
                if (this.size == 0 && this.state.keys.length > INITIAL_CAP) {
                    this.state = new State(INITIAL_CAP);
                }
                return;
            }
            State s = this.state;
            long[] ks = s.keys;
            int[] vs = s.vals;
            int m = s.mask;
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
            if (this.size == 0 && !this.hasZero && ks.length > INITIAL_CAP) {
                this.state = new State(INITIAL_CAP);
                return;
            }
            if (ks.length > INITIAL_CAP && this.size < (ks.length >>> 3)) {
                IntTable shrunk = new IntTable(Math.max(INITIAL_CAP, ks.length >>> 1), this.defaultReturnValue);
                for (int i = 0; i < ks.length; i++) {
                    long k = ks[i];
                    if (k != 0L && k != key) {
                        shrunk.insertFresh(k, vs[i]);
                    }
                }
                VarHandle.releaseFence();
                this.state = shrunk.state;
                return;
            }
            while (true) {
                int last = pos;
                pos = (pos + 1) & m;
                long curr;
                while (true) {
                    curr = ks[pos];
                    if (curr == 0L) {
                        ks[last] = 0L;
                        vs[last] = 0;
                        return;
                    }
                    int slot = ((int) HashCommon.mix(curr)) & m;
                    if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) {
                        break;
                    }
                    pos = (pos + 1) & m;
                }
                vs[last] = vs[pos];
                VarHandle.releaseFence();
                ks[last] = curr;
            }
        }

        private IntTable rehashAndPut(long newKey, int newVal) {
            State s = this.state;
            IntTable next = new IntTable(s.keys.length << 1, this.defaultReturnValue);
            if (this.hasZero) {
                next.zeroVal = this.zeroVal;
                next.hasZero = true;
            }
            long[] ks = s.keys;
            int[] vs = s.vals;
            for (int i = 0; i < ks.length; i++) {
                long k = ks[i];
                if (k != 0L) {
                    next.insertFresh(k, vs[i]);
                }
            }
            next.insertFresh(newKey, newVal);
            VarHandle.releaseFence();
            return next;
        }

        private void insertFresh(long key, int val) {
            State s = this.state;
            int idx = ((int) HashCommon.mix(key)) & s.mask;
            while (s.keys[idx] != 0L) {
                idx = (idx + 1) & s.mask;
            }
            s.vals[idx] = val;
            s.keys[idx] = key;
            this.size++;
        }
    }
}
