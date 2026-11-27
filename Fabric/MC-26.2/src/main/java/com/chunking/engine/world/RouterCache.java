package com.chunking.engine.world;

import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.IdentityHashMap;

/**
 * Per-thread DAG memoization scope for {@code NoiseChunk.<init>} {@code mapAll} passes.
 * <p>
 * Vanilla {@code DensityFunction$1RecursiveVisitor} walks every shared node ({@code offset},
 * {@code factor}, {@code jaggedness}, {@code sloped_cheese}, splines, etc.) repeatedly across
 * router fields. Memoizing by node identity collapses redundant traversals and allocations.
 */
public final class RouterCache {
    public static final class WrapState {
        public boolean active;
        public int depth;
        public boolean sawHolderHolder;
        public final IdentityHashMap<DensityFunction, DensityFunction> memo = new IdentityHashMap<>(512);
    }

    private static final ThreadLocal<WrapState> STATE_TL = ThreadLocal.withInitial(WrapState::new);

    private RouterCache() {}

    public static WrapState beginNewChunk() {
        WrapState s = STATE_TL.get();
        if (s.depth == 0) {
            s.memo.clear();
            s.sawHolderHolder = false;
            s.active = true;
        } else {
            s.active = false;
        }
        s.depth++;
        return s;
    }

    public static void resumeScope() {
        WrapState s = STATE_TL.get();
        if (s.depth == 1) {
            s.active = true;
        }
    }

    public static void pauseScope() {
        STATE_TL.get().active = false;
    }

    public static void endNewChunk() {
        WrapState s = STATE_TL.get();
        if (s.depth > 0) {
            s.depth--;
        }
        if (s.depth == 0) {
            s.active = false;
            s.memo.clear();
        }
    }

    public static WrapState getIfActive() {
        WrapState s = STATE_TL.get();
        return s.active ? s : null;
    }

    public static void seedIdempotent(DensityFunction df) {
        WrapState s = STATE_TL.get();
        if (!s.sawHolderHolder && df != null) {
            s.memo.put(df, df);
        }
    }

    public static void markHolderHolder() {
        WrapState s = STATE_TL.get();
        if (s.active) {
            s.sawHolderHolder = true;
        }
    }
}
