package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.ClimateNodeAccess;
import com.chunking.engine.access.ClimateRTreeAccess;
import com.chunking.engine.world.FlatBiomeTree;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Thread-local RTree query array reuse and exact column depth short-circuits for Climate.RTree.search.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree")
public abstract class ClimateRTreeMixin<T> implements ClimateRTreeAccess<T> {
    @Shadow
    @Final
    private ThreadLocal<?> lastResult;

    @Unique
    private boolean chunking$allDepthNonNeg;

    @Unique
    private boolean chunking$disjointDepthBands;

    @Unique
    private long[] chunking$depthBandMins;

    @Unique
    private long[] chunking$depthBandMaxs;

    @Unique
    private ClimateNodeAccess chunking$rootNode;

    @Unique
    private volatile FlatBiomeTree chunking$flatTree;

    @Unique
    private static volatile Class<?> CHUNKING$LAST_VANILLA_METRIC;

    @Unique
    private static final ClassValue<Boolean> CHUNKING$IS_VANILLA_METRIC = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            return name.startsWith("net.minecraft.world.level.biome.Climate$ParameterList")
                    || name.startsWith("net.minecraft.world.level.biome.Climate$RTree");
        }
    };

    @Unique
    private static final class SearchState {
        final long[] buf = new long[7];
        final int[] dfsCur = new int[64];
        final int[] dfsEnd = new int[64];
        Object lastTree;
        Object lastLeaf;
        int lastLeafIdx = -1;
        long t, h, c, e, w, d;
    }

    @Unique
    private static final ThreadLocal<SearchState> CHUNKING$TL_STATE = ThreadLocal.withInitial(SearchState::new);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$inspectRootDepthBounds(CallbackInfo ci) {
        try {
            Field f = this.getClass().getDeclaredField("root");
            f.setAccessible(true);
            Object rootObj = f.get(this);
            if (rootObj instanceof ClimateNodeAccess acc) {
                this.chunking$rootNode = acc;
                Climate.Parameter[] space = acc.chunking$parameterSpace();
                this.chunking$allDepthNonNeg = space != null && space.length > 4 && space[4].min() >= 0L;
                chunking$analyzeDisjointDepthBands(acc);
                this.chunking$flatTree = FlatBiomeTree.build(acc);
            }
        } catch (Throwable ignored) {
            this.chunking$allDepthNonNeg = false;
            this.chunking$disjointDepthBands = false;
        }
    }

    @Unique
    private void chunking$analyzeDisjointDepthBands(ClimateNodeAccess root) {
        java.util.List<ClimateNodeAccess> leaves = new java.util.ArrayList<>(512);
        chunking$collectLeavesDfs(root, leaves);
        if (leaves.isEmpty()) {
            return;
        }
        long[] mins = new long[32];
        long[] maxs = new long[32];
        int count = 0;
        for (int i = 0, n = leaves.size(); i < n; i++) {
            Climate.Parameter[] ps = leaves.get(i).chunking$parameterSpace();
            if (ps == null || ps.length <= 4) {
                return;
            }
            long mn = ps[4].min();
            long mx = ps[4].max();
            boolean found = false;
            for (int b = 0; b < count; b++) {
                if (mins[b] == mn && maxs[b] == mx) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (count >= 32) {
                    return;
                }
                mins[count] = mn;
                maxs[count] = mx;
                count++;
            }
        }
        for (int i = 0; i < count - 1; i++) {
            for (int j = i + 1; j < count; j++) {
                if (mins[j] < mins[i] || (mins[j] == mins[i] && maxs[j] < maxs[i])) {
                    long tm = mins[i]; mins[i] = mins[j]; mins[j] = tm;
                    long tx = maxs[i]; maxs[i] = maxs[j]; maxs[j] = tx;
                }
            }
        }
        for (int i = 0; i < count - 1; i++) {
            if (maxs[i] >= mins[i + 1]) {
                return;
            }
        }
        this.chunking$depthBandMins = java.util.Arrays.copyOf(mins, count);
        this.chunking$depthBandMaxs = java.util.Arrays.copyOf(maxs, count);
        this.chunking$disjointDepthBands = count > 0;
    }

    @Unique
    private boolean chunking$isInteriorDepthWinnerGuaranteed(long pMin, long pMax, long d, long dist) {
        long[] bMins = this.chunking$depthBandMins;
        long[] bMaxs = this.chunking$depthBandMaxs;
        if (bMins == null || bMaxs == null) {
            return false;
        }
        int len = bMins.length;
        for (int i = 0; i < len; i++) {
            if (bMins[i] == pMin && bMaxs[i] == pMax) {
                if (i > 0) {
                    long gapLo = d - bMaxs[i - 1];
                    if (gapLo <= 0L || (gapLo <= 3_000_000_000L && gapLo * gapLo <= dist)) {
                        return false;
                    }
                }
                if (i + 1 < len) {
                    long gapHi = bMins[i + 1] - d;
                    if (gapHi <= 0L || (gapHi <= 3_000_000_000L && gapHi * gapHi <= dist)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Inject(method = "search", at = @At("HEAD"), cancellable = true)
    private void chunking$fastColumnShortCircuit(
            Climate.TargetPoint tp,
            @Coerce Object metric,
            CallbackInfoReturnable<T> cir
    ) {
        if (!ModConfig.SIMD_NOISE) {
            return;
        }
        Class<?> metricClass = metric.getClass();
        boolean isVanilla = (metricClass == CHUNKING$LAST_VANILLA_METRIC);
        if (!isVanilla && CHUNKING$IS_VANILLA_METRIC.get(metricClass)) {
            CHUNKING$LAST_VANILLA_METRIC = metricClass;
            isVanilla = true;
        }
        if (isVanilla) {
            cir.setReturnValue(this.chunking$searchRaw(
                    tp.temperature(), tp.humidity(), tp.continentalness(),
                    tp.erosion(), tp.depth(), tp.weirdness()));
        }
    }

    @Redirect(
            method = "search",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Climate$TargetPoint;toParameterArray()[J"
            )
    )
    private long[] chunking$reuseParameterArray(Climate.TargetPoint tp) {
        if (!ModConfig.SIMD_NOISE) {
            return new long[]{
                    tp.temperature(), tp.humidity(), tp.continentalness(),
                    tp.erosion(), tp.depth(), tp.weirdness(), 0L
            };
        }
        long[] buf = CHUNKING$TL_STATE.get().buf;
        buf[0] = tp.temperature();
        buf[1] = tp.humidity();
        buf[2] = tp.continentalness();
        buf[3] = tp.erosion();
        buf[4] = tp.depth();
        buf[5] = tp.weirdness();
        buf[6] = 0L;
        return buf;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T chunking$searchRaw(long t, long h, long c, long e, long d, long w) {
        SearchState st = CHUNKING$TL_STATE.get();
        Object leafObj = this.lastResult.get();
        if (leafObj instanceof ClimateNodeAccess nodeAcc) {
            long dist;
            FlatBiomeTree flatCached = this.chunking$flatTree;
            if (flatCached != null && st.lastTree == this && st.lastLeafIdx >= 0
                    && st.lastLeafIdx < flatCached.nodes.length
                    && flatCached.nodes[st.lastLeafIdx] == nodeAcc) {
                dist = FlatBiomeTree.dist7(flatCached.bounds, st.lastLeafIdx, t, h, c, e, d, w);
            } else {
                st.buf[0] = t;
                st.buf[1] = h;
                st.buf[2] = c;
                st.buf[3] = e;
                st.buf[4] = d;
                st.buf[5] = w;
                st.buf[6] = 0L;
                dist = nodeAcc.chunking$distanceFast(st.buf);
            }
            if (dist == 0L) {
                st.lastTree = this;
                st.lastLeaf = nodeAcc;
                st.t = t;
                st.h = h;
                st.c = c;
                st.e = e;
                st.w = w;
                st.d = d;
                return (T) nodeAcc.chunking$leafValue();
            }
            if (st.lastTree == this && st.lastLeaf == nodeAcc
                    && st.t == t && st.h == h && st.c == c && st.e == e && st.w == w) {
                Climate.Parameter[] leafParams = nodeAcc.chunking$parameterSpace();
                long pMin = leafParams[4].min();
                long pMax = leafParams[4].max();
                if (this.chunking$allDepthNonNeg && st.d <= 0L && d <= st.d && pMin == 0L) {
                    st.d = d;
                    return (T) nodeAcc.chunking$leafValue();
                }
                if (this.chunking$disjointDepthBands && st.d >= pMin && st.d <= pMax && d >= pMin && d <= pMax
                        && chunking$isInteriorDepthWinnerGuaranteed(pMin, pMax, d, dist)) {
                    st.d = d;
                    return (T) nodeAcc.chunking$leafValue();
                }
            }
        }
        FlatBiomeTree flat = chunking$ensureFlatTree();
        if (flat != null) {
            ClimateNodeAccess prev = (leafObj instanceof ClimateNodeAccess acc) ? acc : null;
            int prevIdx = -1;
            if (prev != null && st.lastTree == this && st.lastLeafIdx >= 0
                    && st.lastLeafIdx < flat.nodes.length
                    && flat.nodes[st.lastLeafIdx] == prev) {
                prevIdx = st.lastLeafIdx;
            }
            int winIdx = FlatBiomeTree.searchFlat(
                    flat, st.dfsCur, st.dfsEnd, st.buf, prevIdx, prev, t, h, c, e, d, w);
            if (winIdx >= 0 || (winIdx == -1 && prev != null)) {
                ClimateNodeAccess winNode = (winIdx >= 0) ? flat.nodes[winIdx] : prev;
                ((ThreadLocal<Object>) (Object) this.lastResult).set(winNode);
                st.lastTree = this;
                st.lastLeaf = winNode;
                st.lastLeafIdx = winIdx >= 0 ? winIdx : prevIdx;
                st.t = t;
                st.h = h;
                st.c = c;
                st.e = e;
                st.w = w;
                st.d = d;
                return winIdx >= 0 ? (T) flat.leafValues[winIdx] : (T) winNode.chunking$leafValue();
            }
        }
        ClimateNodeAccess root = this.chunking$rootNode;
        if (root == null) {
            try {
                Field f = this.getClass().getDeclaredField("root");
                f.setAccessible(true);
                root = (ClimateNodeAccess) f.get(this);
                this.chunking$rootNode = root;
            } catch (Throwable ignored) {
                return null;
            }
        }
        long[] values = st.buf;
        values[0] = t;
        values[1] = h;
        values[2] = c;
        values[3] = e;
        values[4] = d;
        values[5] = w;
        values[6] = 0L;
        ClimateNodeAccess prev = (leafObj instanceof ClimateNodeAccess acc) ? acc : null;
        ClimateNodeAccess best = chunking$searchNodeRaw(root, values, prev);
        ((ThreadLocal<Object>) (Object) this.lastResult).set(best);
        st.lastTree = this;
        st.lastLeafIdx = -1;
        st.t = t;
        st.h = h;
        st.c = c;
        st.e = e;
        st.w = w;
        st.d = d;
        return (T) best.chunking$leafValue();
    }

    @Unique
    private FlatBiomeTree chunking$ensureFlatTree() {
        FlatBiomeTree ft = this.chunking$flatTree;
        if (ft != null) {
            return ft;
        }
        ClimateNodeAccess root = this.chunking$rootNode;
        if (root == null) {
            try {
                Field f = this.getClass().getDeclaredField("root");
                f.setAccessible(true);
                root = (ClimateNodeAccess) f.get(this);
                this.chunking$rootNode = root;
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (root == null) {
            return null;
        }
        ft = FlatBiomeTree.build(root);
        this.chunking$flatTree = ft;
        return ft;
    }

    @Unique
    private static void chunking$collectLeavesDfs(ClimateNodeAccess node, java.util.List<ClimateNodeAccess> out) {
        ClimateNodeAccess[] children = node.chunking$children();
        if (children == null) {
            out.add(node);
            return;
        }
        for (int i = 0; i < children.length; i++) {
            chunking$collectLeavesDfs(children[i], out);
        }
    }

    @Override
    public boolean chunking$supportsColumnBatch() {
        return this.chunking$ensureFlatTree() != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void chunking$searchColumnBatch(
            long tQ, long hQ, long cQ, long eQ, long wQ,
            long[] dQs, int count,
            Object[] outBiomes, int outOffset, int outStride
    ) {
        FlatBiomeTree flat = chunking$ensureFlatTree();
        if (flat == null) {
            for (int yi = 0; yi < count; yi++) {
                outBiomes[outOffset + yi * outStride] = chunking$searchRaw(tQ, hQ, cQ, eQ, dQs[yi], wQ);
            }
            return;
        }
        SearchState st = CHUNKING$TL_STATE.get();
        int seedIdx = -1;
        Object leafObj = this.lastResult.get();
        if (leafObj instanceof ClimateNodeAccess prev
                && st.lastTree == this
                && st.lastLeafIdx >= 0
                && st.lastLeafIdx < flat.nodes.length
                && flat.nodes[st.lastLeafIdx] == prev) {
            seedIdx = st.lastLeafIdx;
        }
        int lastWinIdx = FlatBiomeTree.searchColumnFast(
                flat, st.dfsCur, st.dfsEnd, st.buf,
                seedIdx,
                this.chunking$allDepthNonNeg,
                this.chunking$disjointDepthBands,
                this.chunking$depthBandMins,
                this.chunking$depthBandMaxs,
                tQ, hQ, cQ, eQ, wQ,
                dQs, count, outBiomes, outOffset, outStride
        );
        if (lastWinIdx >= 0) {
            ClimateNodeAccess winNode = flat.nodes[lastWinIdx];
            ((ThreadLocal<Object>) (Object) this.lastResult).set(winNode);
            st.lastTree = this;
            st.lastLeaf = winNode;
            st.lastLeafIdx = lastWinIdx;
            st.t = tQ;
            st.h = hQ;
            st.c = cQ;
            st.e = eQ;
            st.w = wQ;
            st.d = dQs[count - 1];
        }
    }

    @Unique
    private static ClimateNodeAccess chunking$searchNodeRaw(
            ClimateNodeAccess node,
            long[] target,
            ClimateNodeAccess prevBest
    ) {
        ClimateNodeAccess[] children = node.chunking$children();
        if (children == null) {
            return node;
        }
        long minDist = (prevBest == null) ? Long.MAX_VALUE : prevBest.chunking$distanceFast(target);
        ClimateNodeAccess best = prevBest;
        for (int i = 0; i < children.length; i++) {
            ClimateNodeAccess child = children[i];
            long childDist = child.chunking$distanceFast(target);
            if (minDist > childDist) {
                ClimateNodeAccess candidate = chunking$searchNodeRaw(child, target, best);
                long candDist = (child == candidate) ? childDist : candidate.chunking$distanceFast(target);
                if (minDist > candDist) {
                    minDist = candDist;
                    best = candidate;
                }
            }
        }
        return best;
    }
}
