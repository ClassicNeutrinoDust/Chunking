package com.chunking.engine.world;

import net.minecraft.world.level.biome.Climate;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import com.chunking.engine.access.ClimateNodeAccess;
import com.chunking.engine.simd.NativeAvx2;

/**
 * Flattened primitive-array representation of {@link Climate.RTree} for cache-local,
 * zero-virtual-call 7D nearest-neighbor biome queries.
 *
 * <p>When all 6D climate bounds fit safely within {@code [Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1]}
 * (100% of vanilla and standard modded dimensions), the hot DFS loop uses a compact 48-byte
 * {@code int[12]} per-node layout ({@link #boundsInt}) that fits inside a single 64-byte L1 cache
 * line, paired with a separate 64-bit {@link #offsetSq} array and register-resident {@code int}
 * target coordinates. Extreme modded bounds automatically fall back to the 64-bit {@link #bounds}
 * path with exact parity.</p>
 */
public final class FlatBiomeTree {
    private static final int STRIDE = 13;
    private static final int INT_STRIDE = 12;

    private static final class RawScratch {
        final int[] dfsCur = new int[256];
        final int[] dfsEnd = new int[256];
        final long[] buf = new long[7];
    }

    private static final ThreadLocal<RawScratch> RAW_SCRATCH_TL = ThreadLocal.withInitial(RawScratch::new);

    public final long[] bounds;
    public final int[] boundsInt;
    public final long[] offsetSq;
    public final boolean useIntBounds;
    public final int[] childStart;
    public final int[] childEnd;
    public final Object[] leafValues;
    public final ClimateNodeAccess[] nodes;
    public final MemorySegment nativeNodes;

    private FlatBiomeTree(
            long[] bounds,
            int[] boundsInt,
            long[] offsetSq,
            boolean useIntBounds,
            int[] childStart,
            int[] childEnd,
            Object[] leafValues,
            ClimateNodeAccess[] nodes,
            MemorySegment nativeNodes
    ) {
        this.bounds = bounds;
        this.boundsInt = boundsInt;
        this.offsetSq = offsetSq;
        this.useIntBounds = useIntBounds;
        this.childStart = childStart;
        this.childEnd = childEnd;
        this.leafValues = leafValues;
        this.nodes = nodes;
        this.nativeNodes = nativeNodes;
    }

    private static FlatBiomeTree finalizeTree(
            int count,
            long[] bounds,
            int[] childStart,
            int[] childEnd,
            Object[] leafValues,
            ClimateNodeAccess[] nodes
    ) {
        int[] boundsInt = new int[count * INT_STRIDE];
        long[] offsetSq = new long[count];
        boolean fitsInt = true;
        for (int idx = 0; idx < count; idx++) {
            int lo = idx * STRIDE;
            int io = idx * INT_STRIDE;
            for (int j = 0; j < INT_STRIDE; j++) {
                long v = bounds[lo + j];
                if (v < (long) Integer.MIN_VALUE + 1L || v > (long) Integer.MAX_VALUE - 1L) {
                    fitsInt = false;
                    break;
                }
                boundsInt[io + j] = (int) v;
            }
            if (!fitsInt) {
                break;
            }
            offsetSq[idx] = bounds[lo + 12];
        }
        if (!fitsInt) {
            return new FlatBiomeTree(bounds, null, null, false, childStart, childEnd, leafValues, nodes, null);
        }
        MemorySegment nativeNodes = NativeAvx2.hasRTreeKernel()
                ? NativeAvx2.packFlatRTreeNodes(boundsInt, offsetSq, childStart, childEnd)
                : null;
        return new FlatBiomeTree(bounds, boundsInt, offsetSq, true, childStart, childEnd, leafValues, nodes, nativeNodes);
    }

    public static FlatBiomeTree build(ClimateNodeAccess root) {
        if (root == null) {
            return null;
        }
        int count = countMixinNodes(root);
        long[] bounds = new long[count * STRIDE];
        int[] childStart = new int[count];
        int[] childEnd = new int[count];
        Object[] leafValues = new Object[count];
        ClimateNodeAccess[] nodes = new ClimateNodeAccess[count];
        writeMixinMeta(bounds, nodes, 0, root);
        int[] alloc = new int[]{1};
        fillMixinChildren(bounds, childStart, childEnd, leafValues, nodes, root, 0, alloc);
        return finalizeTree(count, bounds, childStart, childEnd, leafValues, nodes);
    }

    private static int countMixinNodes(ClimateNodeAccess node) {
        ClimateNodeAccess[] children = node.chunking$children();
        if (children == null) {
            return 1;
        }
        int total = 1;
        for (int i = 0; i < children.length; i++) {
            total += countMixinNodes(children[i]);
        }
        return total;
    }

    private static void writeMixinMeta(
            long[] bounds,
            ClimateNodeAccess[] nodes,
            int idx,
            ClimateNodeAccess node
    ) {
        nodes[idx] = node;
        writeParams(bounds, idx, node.chunking$parameterSpace());
    }

    private static void fillMixinChildren(
            long[] bounds,
            int[] childStart,
            int[] childEnd,
            Object[] leafValues,
            ClimateNodeAccess[] nodes,
            ClimateNodeAccess node,
            int idx,
            int[] alloc
    ) {
        ClimateNodeAccess[] children = node.chunking$children();
        if (children == null) {
            childStart[idx] = -1;
            childEnd[idx] = -1;
            leafValues[idx] = node.chunking$leafValue();
            return;
        }
        int k = children.length;
        int start = alloc[0];
        alloc[0] = start + k;
        childStart[idx] = start;
        childEnd[idx] = start + k;
        for (int i = 0; i < k; i++) {
            writeMixinMeta(bounds, nodes, start + i, children[i]);
        }
        for (int i = 0; i < k; i++) {
            fillMixinChildren(bounds, childStart, childEnd, leafValues, nodes, children[i], start + i, alloc);
        }
    }

    public static FlatBiomeTree buildFromRawRoot(Object rootNode) {
        if (rootNode == null) {
            return null;
        }
        if (rootNode instanceof ClimateNodeAccess acc) {
            return build(acc);
        }
        RawNodeReflect r = RawNodeReflect.INSTANCE;
        int count = countRawNodes(r, rootNode);
        long[] bounds = new long[count * STRIDE];
        int[] childStart = new int[count];
        int[] childEnd = new int[count];
        Object[] leafValues = new Object[count];
        ClimateNodeAccess[] nodes = new ClimateNodeAccess[count];
        writeRawMeta(r, bounds, 0, rootNode);
        int[] alloc = new int[]{1};
        fillRawChildren(r, bounds, childStart, childEnd, leafValues, rootNode, 0, alloc);
        return finalizeTree(count, bounds, childStart, childEnd, leafValues, nodes);
    }

    private static int countRawNodes(RawNodeReflect r, Object node) {
        Object[] children = r.childrenOf(node);
        if (children == null) {
            return 1;
        }
        int total = 1;
        for (int i = 0; i < children.length; i++) {
            total += countRawNodes(r, children[i]);
        }
        return total;
    }

    private static void writeRawMeta(RawNodeReflect r, long[] bounds, int idx, Object node) {
        writeParams(bounds, idx, r.paramsOf(node));
    }

    private static void fillRawChildren(
            RawNodeReflect r,
            long[] bounds,
            int[] childStart,
            int[] childEnd,
            Object[] leafValues,
            Object node,
            int idx,
            int[] alloc
    ) {
        Object[] children = r.childrenOf(node);
        if (children == null) {
            childStart[idx] = -1;
            childEnd[idx] = -1;
            leafValues[idx] = r.leafValueOf(node);
            return;
        }
        int k = children.length;
        int start = alloc[0];
        alloc[0] = start + k;
        childStart[idx] = start;
        childEnd[idx] = start + k;
        for (int i = 0; i < k; i++) {
            writeRawMeta(r, bounds, start + i, children[i]);
        }
        for (int i = 0; i < k; i++) {
            fillRawChildren(r, bounds, childStart, childEnd, leafValues, children[i], start + i, alloc);
        }
    }

    private static void writeParams(long[] bounds, int idx, Climate.Parameter[] ps) {
        int o = idx * STRIDE;
        for (int p = 0; p < 6; p++) {
            bounds[o + p * 2] = ps[p].min();
            bounds[o + p * 2 + 1] = ps[p].max();
        }
        long p6Min = ps[6].min();
        long p6Max = ps[6].max();
        long d6 = 0L > p6Max ? -p6Max : (0L < p6Min ? p6Min : 0L);
        bounds[o + 12] = d6 * d6;
    }

    private static boolean allFitInInt(long t, long h, long c, long e, long d, long w) {
        return ((t ^ (int) t) | (h ^ (int) h) | (c ^ (int) c)
                | (e ^ (int) e) | (d ^ (int) d) | (w ^ (int) w)) == 0L;
    }

    private static boolean allFitInInt5(long t, long h, long c, long e, long w) {
        return ((t ^ (int) t) | (h ^ (int) h) | (c ^ (int) c)
                | (e ^ (int) e) | (w ^ (int) w)) == 0L;
    }

    public static long dist7(long[] b, int idx, long t, long h, long c, long e, long d, long w) {
        int o = idx * STRIDE;
        long d0 = t > b[o + 1] ? t - b[o + 1] : (t < b[o] ? b[o] - t : 0L);
        long d1 = h > b[o + 3] ? h - b[o + 3] : (h < b[o + 2] ? b[o + 2] - h : 0L);
        long d2 = c > b[o + 5] ? c - b[o + 5] : (c < b[o + 4] ? b[o + 4] - c : 0L);
        long d3 = e > b[o + 7] ? e - b[o + 7] : (e < b[o + 6] ? b[o + 6] - e : 0L);
        long d4 = d > b[o + 9] ? d - b[o + 9] : (d < b[o + 8] ? b[o + 8] - d : 0L);
        long d5 = w > b[o + 11] ? w - b[o + 11] : (w < b[o + 10] ? b[o + 10] - w : 0L);
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4 + d5 * d5 + b[o + 12];
    }

    public static long dist7Int(
            int[] bi, long[] offsetSq, int idx,
            int t0, int t1, int t2, int t3, int t4, int t5
    ) {
        int o = idx * INT_STRIDE;
        int lo0 = bi[o];
        int hi0 = bi[o + 1];
        long d0 = t0 > hi0 ? (long) t0 - (long) hi0 : (t0 < lo0 ? (long) lo0 - (long) t0 : 0L);

        int lo1 = bi[o + 2];
        int hi1 = bi[o + 3];
        long d1 = t1 > hi1 ? (long) t1 - (long) hi1 : (t1 < lo1 ? (long) lo1 - (long) t1 : 0L);

        int lo2 = bi[o + 4];
        int hi2 = bi[o + 5];
        long d2 = t2 > hi2 ? (long) t2 - (long) hi2 : (t2 < lo2 ? (long) lo2 - (long) t2 : 0L);

        int lo3 = bi[o + 6];
        int hi3 = bi[o + 7];
        long d3 = t3 > hi3 ? (long) t3 - (long) hi3 : (t3 < lo3 ? (long) lo3 - (long) t3 : 0L);

        int lo4 = bi[o + 8];
        int hi4 = bi[o + 9];
        long d4 = t4 > hi4 ? (long) t4 - (long) hi4 : (t4 < lo4 ? (long) lo4 - (long) t4 : 0L);

        int lo5 = bi[o + 10];
        int hi5 = bi[o + 11];
        long d5 = t5 > hi5 ? (long) t5 - (long) hi5 : (t5 < lo5 ? (long) lo5 - (long) t5 : 0L);

        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4 + d5 * d5 + offsetSq[idx];
    }

    private static long dist5PlusOffsetInt(
            int[] bi, long[] offsetSq, int idx,
            int t0, int t1, int t2, int t3, int t5
    ) {
        int o = idx * INT_STRIDE;
        int lo0 = bi[o];
        int hi0 = bi[o + 1];
        long d0 = t0 > hi0 ? (long) t0 - (long) hi0 : (t0 < lo0 ? (long) lo0 - (long) t0 : 0L);

        int lo1 = bi[o + 2];
        int hi1 = bi[o + 3];
        long d1 = t1 > hi1 ? (long) t1 - (long) hi1 : (t1 < lo1 ? (long) lo1 - (long) t1 : 0L);

        int lo2 = bi[o + 4];
        int hi2 = bi[o + 5];
        long d2 = t2 > hi2 ? (long) t2 - (long) hi2 : (t2 < lo2 ? (long) lo2 - (long) t2 : 0L);

        int lo3 = bi[o + 6];
        int hi3 = bi[o + 7];
        long d3 = t3 > hi3 ? (long) t3 - (long) hi3 : (t3 < lo3 ? (long) lo3 - (long) t3 : 0L);

        int lo5 = bi[o + 10];
        int hi5 = bi[o + 11];
        long d5 = t5 > hi5 ? (long) t5 - (long) hi5 : (t5 < lo5 ? (long) lo5 - (long) t5 : 0L);

        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d5 * d5 + offsetSq[idx];
    }

    public static boolean isInteriorDepthWinnerGuaranteed(
            long[] bMins, long[] bMaxs, long pMin, long pMax, long d, long dist
    ) {
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

    /**
     * Evaluates an entire Y-column of {@code count} depth queries at fixed {@code (t, h, c, e, w)}
     * without thrashing {@code ThreadLocal} state, preserving all O(1) sky/depth-band short-circuits.
     *
     * @return the winning leaf index for the last depth query {@code dQs[count - 1]}
     */
    public static int searchColumnFast(
            FlatBiomeTree flat,
            int[] curArr,
            int[] endArr,
            long[] buf,
            int seedLeafIdx,
            boolean allDepthNonNeg,
            boolean disjointDepthBands,
            long[] depthBandMins,
            long[] depthBandMaxs,
            long t, long h, long c, long e, long w,
            long[] dQs, int count,
            Object[] outBiomes, int outOffset, int outStride
    ) {
        if (count <= 0) {
            return -1;
        }
        int[] cStart = flat.childStart;
        Object[] leaves = flat.leafValues;
        if (cStart[0] < 0) {
            Object single = leaves[0];
            for (int yi = 0; yi < count; yi++) {
                outBiomes[outOffset + yi * outStride] = single;
            }
            return 0;
        }

        if (flat.useIntBounds && allFitInInt5(t, h, c, e, w)) {
            return searchColumnFastInt(
                    flat, curArr, endArr, buf, seedLeafIdx,
                    allDepthNonNeg, disjointDepthBands, depthBandMins, depthBandMaxs,
                    (int) t, (int) h, (int) c, (int) e, (int) w,
                    dQs, count, outBiomes, outOffset, outStride
            );
        }

        long[] bounds = flat.bounds;
        int curLeafIdx = (seedLeafIdx >= 0 && seedLeafIdx < leaves.length && cStart[seedLeafIdx] < 0)
                ? seedLeafIdx : -1;
        long curLeafDMin = 0L;
        long curLeafDMax = 0L;
        Object curLeafVal = null;
        if (curLeafIdx >= 0) {
            int o = curLeafIdx * STRIDE;
            curLeafDMin = bounds[o + 8];
            curLeafDMax = bounds[o + 9];
            curLeafVal = leaves[curLeafIdx];
        }

        long prevD = Long.MIN_VALUE;
        boolean hasPrev = false;

        for (int yi = 0; yi < count; yi++) {
            long d = dQs[yi];
            if (hasPrev && d == prevD) {
                outBiomes[outOffset + yi * outStride] = curLeafVal;
                continue;
            }
            if (curLeafIdx >= 0) {
                if (hasPrev && allDepthNonNeg && prevD <= 0L && d <= prevD && curLeafDMin == 0L) {
                    prevD = d;
                    outBiomes[outOffset + yi * outStride] = curLeafVal;
                    continue;
                }
                long dist = dist7(bounds, curLeafIdx, t, h, c, e, d, w);
                if (dist == 0L) {
                    prevD = d;
                    hasPrev = true;
                    outBiomes[outOffset + yi * outStride] = curLeafVal;
                    continue;
                }
                if (hasPrev && disjointDepthBands
                        && prevD >= curLeafDMin && prevD <= curLeafDMax
                        && d >= curLeafDMin && d <= curLeafDMax
                        && isInteriorDepthWinnerGuaranteed(depthBandMins, depthBandMaxs, curLeafDMin, curLeafDMax, d, dist)) {
                    prevD = d;
                    outBiomes[outOffset + yi * outStride] = curLeafVal;
                    continue;
                }
            }
            int winIdx = searchFlatLong(flat, curArr, endArr, buf, curLeafIdx, null, t, h, c, e, d, w);
            if (winIdx >= 0) {
                curLeafIdx = winIdx;
                curLeafVal = leaves[winIdx];
                int o = winIdx * STRIDE;
                curLeafDMin = bounds[o + 8];
                curLeafDMax = bounds[o + 9];
            }
            hasPrev = true;
            prevD = d;
            outBiomes[outOffset + yi * outStride] = curLeafVal;
        }
        return curLeafIdx;
    }

    private static int searchColumnFastInt(
            FlatBiomeTree flat,
            int[] curArr,
            int[] endArr,
            long[] buf,
            int seedLeafIdx,
            boolean allDepthNonNeg,
            boolean disjointDepthBands,
            long[] depthBandMins,
            long[] depthBandMaxs,
            int t0, int t1, int t2, int t3, int t5,
            long[] dQs, int count,
            Object[] outBiomes, int outOffset, int outStride
    ) {
        if (flat.nativeNodes != null && NativeAvx2.hasRTreeKernel()) {
            int lastLeaf = NativeAvx2.searchRTreeColumn(
                    flat.nativeNodes,
                    flat.offsetSq.length,
                    seedLeafIdx,
                    allDepthNonNeg,
                    disjointDepthBands,
                    depthBandMins,
                    depthBandMaxs,
                    t0, t1, t2, t3, t5,
                    dQs,
                    count,
                    flat.leafValues,
                    outBiomes,
                    outOffset,
                    outStride
            );
            if (lastLeaf != Integer.MIN_VALUE) {
                return lastLeaf;
            }
        }
        int[] bi = flat.boundsInt;
        long[] offSq = flat.offsetSq;
        int[] cStart = flat.childStart;
        int[] cEnd = flat.childEnd;
        Object[] leaves = flat.leafValues;

        int curLeafIdx = (seedLeafIdx >= 0 && seedLeafIdx < leaves.length && cStart[seedLeafIdx] < 0)
                ? seedLeafIdx : -1;
        long curLeafDMin = 0L;
        long curLeafDMax = 0L;
        long curLeafBaseDist = 0L;
        Object curLeafVal = null;
        if (curLeafIdx >= 0) {
            int io = curLeafIdx * INT_STRIDE;
            curLeafDMin = bi[io + 8];
            curLeafDMax = bi[io + 9];
            curLeafVal = leaves[curLeafIdx];
            curLeafBaseDist = dist5PlusOffsetInt(bi, offSq, curLeafIdx, t0, t1, t2, t3, t5);
        }

        long prevD = Long.MIN_VALUE;
        boolean hasPrev = false;

        for (int yi = 0; yi < count; yi++) {
            long d = dQs[yi];
            if (hasPrev && d == prevD) {
                outBiomes[outOffset + yi * outStride] = curLeafVal;
                continue;
            }
            if ((d ^ (int) d) == 0L) {
                int t4 = (int) d;
                long minDist = Long.MAX_VALUE;
                if (curLeafIdx >= 0) {
                    if (hasPrev && allDepthNonNeg && prevD <= 0L && d <= prevD && curLeafDMin == 0L) {
                        prevD = d;
                        outBiomes[outOffset + yi * outStride] = curLeafVal;
                        continue;
                    }
                    long dd = d > curLeafDMax ? d - curLeafDMax : (d < curLeafDMin ? curLeafDMin - d : 0L);
                    long dist = curLeafBaseDist + dd * dd;
                    if (dist == 0L) {
                        prevD = d;
                        hasPrev = true;
                        outBiomes[outOffset + yi * outStride] = curLeafVal;
                        continue;
                    }
                    if (hasPrev && disjointDepthBands
                            && prevD >= curLeafDMin && prevD <= curLeafDMax
                            && d >= curLeafDMin && d <= curLeafDMax
                            && isInteriorDepthWinnerGuaranteed(depthBandMins, depthBandMaxs, curLeafDMin, curLeafDMax, d, dist)) {
                        prevD = d;
                        outBiomes[outOffset + yi * outStride] = curLeafVal;
                        continue;
                    }
                    minDist = dist;
                }
                int winIdx = searchFlatIntLoop(bi, offSq, cStart, cEnd, curArr, endArr, minDist, t0, t1, t2, t3, t4, t5);
                if (winIdx >= 0) {
                    curLeafIdx = winIdx;
                    curLeafVal = leaves[winIdx];
                    int io = winIdx * INT_STRIDE;
                    curLeafDMin = bi[io + 8];
                    curLeafDMax = bi[io + 9];
                    curLeafBaseDist = dist5PlusOffsetInt(bi, offSq, winIdx, t0, t1, t2, t3, t5);
                }
            } else {
                if (curLeafIdx >= 0) {
                    if (hasPrev && allDepthNonNeg && prevD <= 0L && d <= prevD && curLeafDMin == 0L) {
                        prevD = d;
                        outBiomes[outOffset + yi * outStride] = curLeafVal;
                        continue;
                    }
                    long dist = dist7(flat.bounds, curLeafIdx, t0, t1, t2, t3, d, t5);
                    if (dist == 0L) {
                        prevD = d;
                        hasPrev = true;
                        outBiomes[outOffset + yi * outStride] = curLeafVal;
                        continue;
                    }
                    if (hasPrev && disjointDepthBands
                            && prevD >= curLeafDMin && prevD <= curLeafDMax
                            && d >= curLeafDMin && d <= curLeafDMax
                            && isInteriorDepthWinnerGuaranteed(depthBandMins, depthBandMaxs, curLeafDMin, curLeafDMax, d, dist)) {
                        prevD = d;
                        outBiomes[outOffset + yi * outStride] = curLeafVal;
                        continue;
                    }
                }
                int winIdx = searchFlatLong(flat, curArr, endArr, buf, curLeafIdx, null, t0, t1, t2, t3, d, t5);
                if (winIdx >= 0) {
                    curLeafIdx = winIdx;
                    curLeafVal = leaves[winIdx];
                    int io = winIdx * INT_STRIDE;
                    curLeafDMin = bi[io + 8];
                    curLeafDMax = bi[io + 9];
                    curLeafBaseDist = dist5PlusOffsetInt(bi, offSq, winIdx, t0, t1, t2, t3, t5);
                }
            }
            hasPrev = true;
            prevD = d;
            outBiomes[outOffset + yi * outStride] = curLeafVal;
        }
        return curLeafIdx;
    }

    public static int searchFlat(
            FlatBiomeTree flat,
            int[] curArr,
            int[] endArr,
            long[] buf,
            int prevIdx,
            ClimateNodeAccess prevNode,
            long t, long h, long c, long e, long d, long w
    ) {
        if (flat.useIntBounds && allFitInInt(t, h, c, e, d, w)) {
            return searchFlatInt(
                    flat, curArr, endArr, buf, prevIdx, prevNode,
                    (int) t, (int) h, (int) c, (int) e, (int) d, (int) w
            );
        }
        return searchFlatLong(flat, curArr, endArr, buf, prevIdx, prevNode, t, h, c, e, d, w);
    }

    public static int searchFlatInt(
            FlatBiomeTree flat,
            int[] curArr,
            int[] endArr,
            long[] buf,
            int prevIdx,
            ClimateNodeAccess prevNode,
            int t0, int t1, int t2, int t3, int t4, int t5
    ) {
        int[] cStart = flat.childStart;
        if (cStart[0] < 0) {
            return 0;
        }
        int[] bi = flat.boundsInt;
        long[] offSq = flat.offsetSq;
        long minDist;
        if (prevIdx >= 0) {
            minDist = dist7Int(bi, offSq, prevIdx, t0, t1, t2, t3, t4, t5);
        } else if (prevNode != null) {
            buf[0] = t0;
            buf[1] = t1;
            buf[2] = t2;
            buf[3] = t3;
            buf[4] = t4;
            buf[5] = t5;
            buf[6] = 0L;
            minDist = prevNode.chunking$distanceFast(buf);
        } else {
            minDist = Long.MAX_VALUE;
        }
        if (minDist == 0L) {
            return -1;
        }
        return searchFlatIntLoop(bi, offSq, cStart, flat.childEnd, curArr, endArr, minDist, t0, t1, t2, t3, t4, t5);
    }

    private static int searchFlatIntLoop(
            int[] bi,
            long[] offSq,
            int[] cStart,
            int[] cEnd,
            int[] curArr,
            int[] endArr,
            long minDist,
            int t0, int t1, int t2, int t3, int t4, int t5
    ) {
        int bestIdx = -1;
        int sp = 0;
        curArr[0] = cStart[0];
        endArr[0] = cEnd[0];
        while (sp >= 0) {
            int idx = curArr[sp];
            if (idx >= endArr[sp]) {
                sp--;
                continue;
            }
            curArr[sp] = idx + 1;
            long cd = dist7Int(bi, offSq, idx, t0, t1, t2, t3, t4, t5);
            if (minDist > cd) {
                int cs = cStart[idx];
                if (cs < 0) {
                    if (cd == 0L) {
                        return idx;
                    }
                    minDist = cd;
                    bestIdx = idx;
                } else {
                    if (sp + 1 >= curArr.length) {
                        int newCap = Math.max(64, curArr.length << 1);
                        curArr = java.util.Arrays.copyOf(curArr, newCap);
                        endArr = java.util.Arrays.copyOf(endArr, newCap);
                    }
                    sp++;
                    curArr[sp] = cs;
                    endArr[sp] = cEnd[idx];
                }
            }
        }
        return bestIdx;
    }

    private static int searchFlatLong(
            FlatBiomeTree flat,
            int[] curArr,
            int[] endArr,
            long[] buf,
            int prevIdx,
            ClimateNodeAccess prevNode,
            long t, long h, long c, long e, long d, long w
    ) {
        long[] bounds = flat.bounds;
        int[] cStart = flat.childStart;
        int[] cEnd = flat.childEnd;
        if (cStart[0] < 0) {
            return 0;
        }
        long minDist;
        if (prevIdx >= 0) {
            minDist = dist7(bounds, prevIdx, t, h, c, e, d, w);
        } else if (prevNode != null) {
            buf[0] = t;
            buf[1] = h;
            buf[2] = c;
            buf[3] = e;
            buf[4] = d;
            buf[5] = w;
            buf[6] = 0L;
            minDist = prevNode.chunking$distanceFast(buf);
        } else {
            minDist = Long.MAX_VALUE;
        }
        if (minDist == 0L) {
            return -1;
        }

        int bestIdx = -1;
        int sp = 0;
        curArr[0] = cStart[0];
        endArr[0] = cEnd[0];
        while (sp >= 0) {
            int idx = curArr[sp];
            if (idx >= endArr[sp]) {
                sp--;
                continue;
            }
            curArr[sp] = idx + 1;
            long cd = dist7(bounds, idx, t, h, c, e, d, w);
            if (minDist > cd) {
                int cs = cStart[idx];
                if (cs < 0) {
                    if (cd == 0L) {
                        return idx;
                    }
                    minDist = cd;
                    bestIdx = idx;
                } else {
                    if (sp + 1 >= curArr.length) {
                        int newCap = Math.max(64, curArr.length << 1);
                        curArr = java.util.Arrays.copyOf(curArr, newCap);
                        endArr = java.util.Arrays.copyOf(endArr, newCap);
                    }
                    sp++;
                    curArr[sp] = cs;
                    endArr[sp] = cEnd[idx];
                }
            }
        }
        return bestIdx;
    }

    public Object searchFlatRaw(long t, long h, long c, long e, long d, long w) {
        RawScratch scratch = RAW_SCRATCH_TL.get();
        int idx = searchFlat(this, scratch.dfsCur, scratch.dfsEnd, scratch.buf, -1, null, t, h, c, e, d, w);
        return idx >= 0 ? this.leafValues[idx] : null;
    }

    private static final class RawNodeReflect {
        static final RawNodeReflect INSTANCE = new RawNodeReflect();
        private final Field paramSpaceField;
        private final Field childrenField;
        private final Class<?> subTreeClass;
        private final Field leafValueField;

        private RawNodeReflect() {
            try {
                Class<?> nodeCls = Class.forName("net.minecraft.world.level.biome.Climate$RTree$Node");
                this.paramSpaceField = nodeCls.getDeclaredField("parameterSpace");
                this.paramSpaceField.setAccessible(true);

                this.subTreeClass = Class.forName("net.minecraft.world.level.biome.Climate$RTree$SubTree");
                this.childrenField = this.subTreeClass.getDeclaredField("children");
                this.childrenField.setAccessible(true);

                Class<?> leafCls = Class.forName("net.minecraft.world.level.biome.Climate$RTree$Leaf");
                this.leafValueField = leafCls.getDeclaredField("value");
                this.leafValueField.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize Climate.RTree node reflection", e);
            }
        }

        Climate.Parameter[] paramsOf(Object node) {
            try {
                return (Climate.Parameter[]) this.paramSpaceField.get(node);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        Object[] childrenOf(Object node) {
            if (!this.subTreeClass.isInstance(node)) {
                return null;
            }
            try {
                return (Object[]) this.childrenField.get(node);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        Object leafValueOf(Object node) {
            try {
                return this.leafValueField.get(node);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
