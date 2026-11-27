package com.chunking.engine.simd;

import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.chunking.engine.access.BeardifierAccess;
import com.chunking.engine.access.CacheAllInCellAccess;
import com.chunking.engine.access.FlatCacheAccess;
import com.chunking.engine.world.ModCompat;
import com.chunking.engine.access.NoiseInterpolatorAccess;

/**
 * Sound per-cell interval arithmetic evaluator for {@code NoiseChunk} density DAGs.
 * <ul>
 *   <li>Proves entire {@code 4x8x4} cells strictly {@code <= 0.0D} (all-air/water) or
 *       strictly {@code > 0.0D} (all-solid) in a single flat monomorphic topological switch loop
 *       with zero virtual node dispatch and zero per-node stamp overhead.</li>
 *   <li>Partitions nodes into {@code [const | yInvariant | yDependent]} topological ranges so
 *       constant nodes never execute and 2D {@code yInvariant} subtrees run once per {@code (cellX, cellZ)} column.</li>
 *   <li>Evaluates {@link DensityFunctions.Spline} over {@code FlatCache} coordinates as exact
 *       singleton intervals {@code [v, v]} (eliminating Moore interval blowup on Overworld
 *       {@code offset}, {@code factor}, and {@code jaggedness}).</li>
 * </ul>
 */
public final class CellSkipper {
    private static final byte OP_CONST = 0;
    private static final byte OP_BEARDIFIER = 1;
    private static final byte OP_INTERP = 2;
    private static final byte OP_FLAT_CACHE = 3;
    private static final byte OP_FLAT_SPLINE = 4;
    private static final byte OP_YGRAD = 5;
    private static final byte OP_MUL_CONST = 6;
    private static final byte OP_ADD_CONST = 7;
    private static final byte OP_ADD = 8;
    private static final byte OP_MIN = 9;
    private static final byte OP_MAX = 10;
    private static final byte OP_MUL = 11;
    private static final byte OP_CLAMP = 12;
    private static final byte OP_RANGE_CHOICE = 13;
    private static final byte OP_ABS = 14;
    private static final byte OP_SQUARE = 15;
    private static final byte OP_CUBE = 16;
    private static final byte OP_HALF_NEG = 17;
    private static final byte OP_QUARTER_NEG = 18;
    private static final byte OP_SQUEEZE = 19;
    private static final byte OP_INVERT = 20;
    private static final byte OP_MOD_BOUND = 21;

    private static final byte BEARD_EMPTY = 0;
    private static final byte BEARD_UNBOUNDED = 1;
    private static final byte BEARD_BOX = 2;

    private static final int MOD_PROBE_CELL_TARGET = 1024;
    private static final double MOD_BOUND_EPS = 1e-7D;

    private static final class ModBoundState {
        volatile boolean quarantined;
        volatile boolean verified;
        final AtomicBoolean staticProbed = new AtomicBoolean();
        final AtomicInteger verifiedCells = new AtomicInteger();
    }

    private static final ModBoundState[] EMPTY_MOD_STATES = new ModBoundState[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static volatile int modQuarantineEpoch;

    private static final ClassValue<ModBoundState> MOD_BOUND_STATES = new ClassValue<>() {
        @Override
        protected ModBoundState computeValue(Class<?> type) {
            return new ModBoundState();
        }
    };

    private static final MethodHandle INTERP_SLICE0_GETTER = resolveInterpSliceGetter("slice0");
    private static final MethodHandle INTERP_SLICE1_GETTER = resolveInterpSliceGetter("slice1");

    private static MethodHandle resolveInterpSliceGetter(String fieldName) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    NoiseChunk.NoiseInterpolator.class, MethodHandles.lookup());
            Field f = NoiseChunk.NoiseInterpolator.class.getDeclaredField(fieldName);
            return lookup.unreflectGetter(f).asType(MethodType.methodType(double[][].class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final int[][] STATIC_PROBE_COORDS = {
            { 0, 64, 0 }, { 0, -48, 0 }, { 0, 200, 0 }, { 64, 32, -64 },
            { -128, 96, 128 }, { 512, 0, -512 }, { -1024, 160, 1024 }, { 2048, -32, 2048 }
    };

    private static void probeModDfStatic(DensityFunction df, double mn, double mx, ModBoundState state) {
        try {
            for (int[] c : STATIC_PROBE_COORDS) {
                double v = df.compute(new DensityFunction.SinglePointContext(c[0], c[1], c[2]));
                if (Double.isNaN(v) || v < mn - MOD_BOUND_EPS || v > mx + MOD_BOUND_EPS) {
                    state.quarantined = true;
                    modQuarantineEpoch++;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Chunk-context-dependent mod DFs are verified live during first-chunk cell evaluation.
        }
    }

    public static boolean isModDensityFunctionQuarantined(Class<?> dfClass) {
        return dfClass != null && MOD_BOUND_STATES.get(dfClass).quarantined;
    }

    public static void verifyModBoundSample(DensityFunction df, double sampleValue) {
        if (df == null || ModCompat.isVanillaClass(df.getClass())) {
            return;
        }
        double mn = df.minValue();
        double mx = df.maxValue();
        if (!Double.isFinite(mn) || !Double.isFinite(mx) || Double.isNaN(sampleValue)
                || sampleValue < mn - MOD_BOUND_EPS || sampleValue > mx + MOD_BOUND_EPS) {
            ModBoundState st = MOD_BOUND_STATES.get(df.getClass());
            if (!st.quarantined) {
                st.quarantined = true;
                modQuarantineEpoch++;
            }
        }
    }

    public static boolean interpRangeBounds(
            Object interpObj, int cellZ, int cellYLo, int cellYHi, double[] outMinMax) {
        if (interpObj == null || INTERP_SLICE0_GETTER == null || INTERP_SLICE1_GETTER == null
                || cellZ < 0 || cellYLo < 0 || cellYHi < cellYLo) {
            return false;
        }
        try {
            double[][] s0 = (double[][]) INTERP_SLICE0_GETTER.invokeExact(interpObj);
            double[][] s1 = (double[][]) INTERP_SLICE1_GETTER.invokeExact(interpObj);
            if (s0 == null || s1 == null || cellZ + 1 >= s0.length || cellZ + 1 >= s1.length) {
                return false;
            }
            double[] c00 = s0[cellZ];
            double[] c01 = s0[cellZ + 1];
            double[] c10 = s1[cellZ];
            double[] c11 = s1[cellZ + 1];
            int kEnd = cellYHi + 1;
            if (c00 == null || c01 == null || c10 == null || c11 == null
                    || kEnd >= c00.length || kEnd >= c01.length || kEnd >= c10.length || kEnd >= c11.length) {
                return false;
            }
            double mn = Double.POSITIVE_INFINITY;
            double mx = Double.NEGATIVE_INFINITY;
            for (int k = cellYLo; k <= kEnd; k++) {
                double v0 = c00[k], v1 = c01[k], v2 = c10[k], v3 = c11[k];
                double lo01 = Math.min(v0, v1), hi01 = Math.max(v0, v1);
                double lo23 = Math.min(v2, v3), hi23 = Math.max(v2, v3);
                mn = Math.min(mn, Math.min(lo01, lo23));
                mx = Math.max(mx, Math.max(hi01, hi23));
            }
            outMinMax[0] = mn;
            outMinMax[1] = mx;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final ClassValue<Map<String, MethodHandle>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, MethodHandle> computeValue(Class<?> type) {
            Map<String, MethodHandle> map = new HashMap<>();
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            for (Method m : type.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && !m.isSynthetic()) {
                    try {
                        m.setAccessible(true);
                        map.putIfAbsent(m.getName(), lookup.unreflect(m));
                    } catch (Throwable ignored) {
                    }
                }
            }
            for (Class<?> sup = type.getSuperclass(); sup != null && sup != Object.class; sup = sup.getSuperclass()) {
                for (Method m : sup.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && !m.isSynthetic()) {
                        try {
                            m.setAccessible(true);
                            map.putIfAbsent(m.getName(), lookup.unreflect(m));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return Map.copyOf(map);
        }
    };

    private static final class FlatSplineAux {
        final JitSupport.FlatSpline spline;
        final int[] coordIndices;
        final float[] scratch;

        FlatSplineAux(JitSupport.FlatSpline spline, int[] coordIndices, float[] scratch) {
            this.spline = spline;
            this.coordIndices = coordIndices;
            this.scratch = scratch;
        }
    }

    private static final class RawNode {
        final byte op;
        final byte subOp;
        final int left;
        final int right;
        final int third;
        final double paramA;
        final double paramB;
        final Object aux;
        final boolean yInvariant;

        RawNode(byte op, byte subOp, int left, int right, int third,
                double paramA, double paramB, Object aux, boolean yInvariant) {
            this.op = op;
            this.subOp = subOp;
            this.left = left;
            this.right = right;
            this.third = third;
            this.paramA = paramA;
            this.paramB = paramB;
            this.aux = aux;
            this.yInvariant = yInvariant;
        }
    }

    // Flat topological arrays:
    //   [0 .. constCount - 1]        : OP_CONST (pre-populated in lo/hi, never evaluated)
    //   [constCount .. yInvEnd - 1]  : y-invariant 2D nodes (evaluated once per (baseX, baseZ) column)
    //   [yInvEnd .. op.length - 1]   : y-dependent 3D nodes (evaluated per cell)
    private final byte[] op;
    private final byte[] subOp;
    private final int[] left;
    private final int[] right;
    private final int[] third;
    private final double[] paramA;
    private final double[] paramB;
    private final Object[] aux;
    private final double[] lo;
    private final double[] hi;
    private final int constCount;
    private final int yInvEnd;
    private final int rootIdx;
    private final int cwMinus1;
    private final int[] interpNodeIndices;
    private final int[] flatNodeIndices;
    private final int[] beardNodeIndices;
    private final int[] beardBounds;
    private final ModBoundState[] modBoundStates;
    private final double[] interpRangeScratch = new double[2];

    private int lastColX = Integer.MIN_VALUE;
    private int lastColZ = Integer.MIN_VALUE;
    private int lastQuarantineEpoch = -1;
    private Object boundOwner;

    private CellSkipper(
            byte[] op,
            byte[] subOp,
            int[] left,
            int[] right,
            int[] third,
            double[] paramA,
            double[] paramB,
            Object[] aux,
            int constCount,
            int yInvEnd,
            int rootIdx,
            int cellWidth,
            int[] interpNodeIndices,
            int[] flatNodeIndices,
            int[] beardNodeIndices,
            int[] beardBounds,
            ModBoundState[] modBoundStates
    ) {
        int n = op.length;
        this.op = op;
        this.subOp = subOp;
        this.left = left;
        this.right = right;
        this.third = third;
        this.paramA = paramA;
        this.paramB = paramB;
        this.aux = aux;
        this.lo = new double[n];
        this.hi = new double[n];
        for (int i = 0; i < constCount; i++) {
            this.lo[i] = paramA[i];
            this.hi[i] = paramB[i];
        }
        this.constCount = constCount;
        this.yInvEnd = yInvEnd;
        this.rootIdx = rootIdx;
        this.cwMinus1 = cellWidth - 1;
        this.interpNodeIndices = interpNodeIndices;
        this.flatNodeIndices = flatNodeIndices;
        this.beardNodeIndices = beardNodeIndices;
        this.beardBounds = beardBounds;
        this.modBoundStates = modBoundStates;
    }

    private static double squeeze(double x) {
        double c = Mth.clamp(x, -1.0D, 1.0D);
        return c / 2.0D - c * c * c / 24.0D;
    }

    public boolean hasUnverifiedModBounds() {
        ModBoundState[] states = this.modBoundStates;
        for (int i = 0; i < states.length; i++) {
            ModBoundState s = states[i];
            if (!s.verified && !s.quarantined) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verifies actual evaluated cell values against a non-zero interval classification.
     * Auto-quarantines unverified mod DF classes on any sign violation so buggy mods cannot cause terrain holes.
     */
    public boolean verifyCellValues(int predictedCls, double[] actualValues) {
        if (predictedCls == 0 || actualValues == null || this.modBoundStates.length == 0) {
            return true;
        }
        boolean violated = false;
        if (predictedCls < 0) {
            for (int i = 0; i < actualValues.length; i++) {
                if (actualValues[i] > 0.0D) {
                    violated = true;
                    break;
                }
            }
        } else {
            for (int i = 0; i < actualValues.length; i++) {
                if (actualValues[i] <= 0.0D) {
                    violated = true;
                    break;
                }
            }
        }
        ModBoundState[] states = this.modBoundStates;
        if (violated) {
            for (int i = 0; i < states.length; i++) {
                if (!states[i].verified) {
                    states[i].quarantined = true;
                }
            }
            modQuarantineEpoch++;
            this.lastColX = Integer.MIN_VALUE;
            return false;
        }
        for (int i = 0; i < states.length; i++) {
            ModBoundState s = states[i];
            if (!s.verified && !s.quarantined && s.verifiedCells.incrementAndGet() >= MOD_PROBE_CELL_TARGET) {
                s.verified = true;
            }
        }
        return true;
    }

    /**
     * Coarse vertical range classification over {@code [startY, endY]} inclusive.
     */
    public int classifyRange(int baseX, int startY, int endY, int baseZ) {
        return classifyRange(baseX, startY, endY, baseZ, -1, -1, -1);
    }

    /**
     * Coarse vertical range classification spanning cells {@code [cellYLo, cellYHi]} inclusive.
     * Precomputes exact multi-cell {@code OP_INTERP} slice enclosures before evaluating the flat DAG.
     */
    public int classifyRange(int baseX, int startY, int endY, int baseZ, int cellZ, int cellYLo, int cellYHi) {
        if (startY > endY) {
            return 0;
        }
        if (this.yInvEnd > this.constCount) {
            int qEpoch = this.modBoundStates.length > 0 ? modQuarantineEpoch : this.lastQuarantineEpoch;
            if (baseX != this.lastColX || baseZ != this.lastColZ || qEpoch != this.lastQuarantineEpoch) {
                evalRange(this.constCount, this.yInvEnd, baseX, startY, endY, baseZ, 1.0D);
                this.lastColX = baseX;
                this.lastColZ = baseZ;
                this.lastQuarantineEpoch = qEpoch;
            }
        }
        int[] iNodes = this.interpNodeIndices;
        if (iNodes.length > 0) {
            double[] scratch = this.interpRangeScratch;
            boolean canReadSlices = cellZ >= 0 && cellYLo >= 0 && cellYHi >= cellYLo;
            for (int k = 0; k < iNodes.length; k++) {
                int idx = iNodes[k];
                if (canReadSlices && interpRangeBounds(this.aux[idx], cellZ, cellYLo, cellYHi, scratch)) {
                    this.lo[idx] = scratch[0];
                    this.hi[idx] = scratch[1];
                } else {
                    this.lo[idx] = Double.NEGATIVE_INFINITY;
                    this.hi[idx] = Double.POSITIVE_INFINITY;
                }
            }
        }
        int n = this.op.length;
        if (this.yInvEnd < n) {
            // Negative tMax signals evalRange to preserve precomputed multi-cell OP_INTERP bounds.
            evalRange(this.yInvEnd, n, baseX, startY, endY, baseZ, -1.0D);
        }
        int r = this.rootIdx;
        if (this.hi[r] <= 0.0D) {
            return -1;
        }
        if (this.lo[r] > 0.0D) {
            return 1;
        }
        return 0;
    }

    /**
     * @return {@code -1} if all voxels in the cell are guaranteed {@code <= 0.0D},
     *         {@code +1} if all voxels are guaranteed {@code > 0.0D}, or {@code 0} if mixed/unknown.
     */
    public int classifyCell(int baseX, int startY, int baseZ, int cellHeight) {
        int endY = startY + cellHeight - 1;
        double tMax = cellHeight > 1 ? ((double) (cellHeight - 1) / (double) cellHeight) : 1.0D;
        if (this.yInvEnd > this.constCount) {
            int qEpoch = this.modBoundStates.length > 0 ? modQuarantineEpoch : this.lastQuarantineEpoch;
            if (baseX != this.lastColX || baseZ != this.lastColZ || qEpoch != this.lastQuarantineEpoch) {
                evalRange(this.constCount, this.yInvEnd, baseX, startY, endY, baseZ, tMax);
                this.lastColX = baseX;
                this.lastColZ = baseZ;
                this.lastQuarantineEpoch = qEpoch;
            }
        }
        int n = this.op.length;
        if (this.yInvEnd < n) {
            evalRange(this.yInvEnd, n, baseX, startY, endY, baseZ, tMax);
        }
        int r = this.rootIdx;
        if (this.hi[r] <= 0.0D) {
            return -1;
        }
        if (this.lo[r] > 0.0D) {
            return 1;
        }
        return 0;
    }

    private void evalRange(int from, int to, int baseX, int startY, int endY, int baseZ, double tMax) {
        byte[] ops = this.op;
        byte[] subOps = this.subOp;
        int[] lefts = this.left;
        int[] rights = this.right;
        int[] thirds = this.third;
        double[] pA = this.paramA;
        double[] pB = this.paramB;
        Object[] auxs = this.aux;
        double[] los = this.lo;
        double[] his = this.hi;
        int cwM1 = this.cwMinus1;

        for (int i = from; i < to; i++) {
            switch (ops[i]) {
                case OP_CONST -> {
                }
                case OP_BEARDIFIER -> {
                    byte mode = subOps[i];
                    if (mode == BEARD_EMPTY) {
                        los[i] = 0.0D;
                        his[i] = 0.0D;
                    } else if (mode == BEARD_UNBOUNDED) {
                        los[i] = pA[i];
                        his[i] = pB[i];
                    } else {
                        int off = lefts[i];
                        int[] bb = this.beardBounds;
                        if (endY < bb[off + 1] || startY > bb[off + 4]
                                || (baseX + cwM1) < bb[off] || baseX > bb[off + 3]
                                || (baseZ + cwM1) < bb[off + 2] || baseZ > bb[off + 5]) {
                            los[i] = 0.0D;
                            his[i] = 0.0D;
                        } else {
                            los[i] = pA[i];
                            his[i] = pB[i];
                        }
                    }
                }
                case OP_INTERP -> {
                    if (tMax < 0.0D) {
                        break;
                    }
                    NoiseInterpolatorAccess ip = (NoiseInterpolatorAccess) auxs[i];
                    double c000 = ip.chunking$getNoise000();
                    double c001 = ip.chunking$getNoise001();
                    double c010 = ip.chunking$getNoise010();
                    double c011 = ip.chunking$getNoise011();
                    double c100 = ip.chunking$getNoise100();
                    double c101 = ip.chunking$getNoise101();
                    double c110 = ip.chunking$getNoise110();
                    double c111 = ip.chunking$getNoise111();
                    double top00 = c000 + tMax * (c010 - c000);
                    double top01 = c001 + tMax * (c011 - c001);
                    double top10 = c100 + tMax * (c110 - c100);
                    double top11 = c101 + tMax * (c111 - c101);
                    los[i] = Math.min(
                            Math.min(Math.min(c000, c001), Math.min(c100, c101)),
                            Math.min(Math.min(top00, top01), Math.min(top10, top11)));
                    his[i] = Math.max(
                            Math.max(Math.max(c000, c001), Math.max(c100, c101)),
                            Math.max(Math.max(top00, top01), Math.max(top10, top11)));
                }
                case OP_FLAT_CACHE -> {
                    double v = ((FlatCacheAccess) auxs[i]).chunking$sampleQuartFast(baseX, baseZ);
                    los[i] = v;
                    his[i] = v;
                }
                case OP_FLAT_SPLINE -> {
                    FlatSplineAux sp = (FlatSplineAux) auxs[i];
                    int[] cs = sp.coordIndices;
                    float[] scratch = sp.scratch;
                    boolean exactPoint = true;
                    for (int k = 0; k < cs.length; k++) {
                        int ci = cs[k];
                        double cLo = los[ci];
                        if (cLo != his[ci]) {
                            exactPoint = false;
                            break;
                        }
                        scratch[k] = (float) cLo;
                    }
                    if (exactPoint) {
                        double val = JitSupport.evalFlatSpline(sp.spline, scratch);
                        los[i] = val;
                        his[i] = val;
                    } else {
                        los[i] = pA[i];
                        his[i] = pB[i];
                    }
                }
                case OP_YGRAD -> {
                    double fY = lefts[i];
                    double tY = rights[i];
                    double fV = pA[i];
                    double tV = pB[i];
                    double v0 = Mth.clampedMap((double) startY, fY, tY, fV, tV);
                    double v1 = Mth.clampedMap((double) endY, fY, tY, fV, tV);
                    los[i] = Math.min(v0, v1);
                    his[i] = Math.max(v0, v1);
                }
                case OP_MUL_CONST -> {
                    int a = lefts[i];
                    double arg = pA[i];
                    double p0 = los[a] * arg;
                    double p1 = his[a] * arg;
                    los[i] = Math.min(p0, p1);
                    his[i] = Math.max(p0, p1);
                }
                case OP_ADD_CONST -> {
                    int a = lefts[i];
                    double arg = pA[i];
                    los[i] = los[a] + arg;
                    his[i] = his[a] + arg;
                }
                case OP_ADD -> {
                    int a = lefts[i];
                    int b = rights[i];
                    los[i] = los[a] + los[b];
                    his[i] = his[a] + his[b];
                }
                case OP_MIN -> {
                    int a = lefts[i];
                    int b = rights[i];
                    los[i] = Math.min(los[a], los[b]);
                    his[i] = Math.min(his[a], his[b]);
                }
                case OP_MAX -> {
                    int a = lefts[i];
                    int b = rights[i];
                    los[i] = Math.max(los[a], los[b]);
                    his[i] = Math.max(his[a], his[b]);
                }
                case OP_MUL -> {
                    int a = lefts[i];
                    int b = rights[i];
                    double a0 = los[a], a1 = his[a];
                    double b0 = los[b], b1 = his[b];
                    double p0 = a0 * b0;
                    double p1 = a0 * b1;
                    double p2 = a1 * b0;
                    double p3 = a1 * b1;
                    los[i] = Math.min(Math.min(p0, p1), Math.min(p2, p3));
                    his[i] = Math.max(Math.max(p0, p1), Math.max(p2, p3));
                }
                case OP_CLAMP -> {
                    int a = lefts[i];
                    double cMin = pA[i];
                    double cMax = pB[i];
                    los[i] = Mth.clamp(los[a], cMin, cMax);
                    his[i] = Mth.clamp(his[a], cMin, cMax);
                }
                case OP_RANGE_CHOICE -> {
                    int inp = thirds[i];
                    double iLo = los[inp];
                    double iHi = his[inp];
                    double minInc = pA[i];
                    double maxExc = pB[i];
                    if (iHi < minInc || iLo >= maxExc) {
                        int r = rights[i];
                        los[i] = los[r];
                        his[i] = his[r];
                    } else if (iLo >= minInc && iHi < maxExc) {
                        int l = lefts[i];
                        los[i] = los[l];
                        his[i] = his[l];
                    } else {
                        int l = lefts[i];
                        int r = rights[i];
                        los[i] = Math.min(los[l], los[r]);
                        his[i] = Math.max(his[l], his[r]);
                    }
                }
                case OP_ABS -> {
                    int idx = lefts[i];
                    double a = los[idx];
                    double b = his[idx];
                    if (a >= 0.0D) {
                        los[i] = a;
                        his[i] = b;
                    } else if (b <= 0.0D) {
                        los[i] = -b;
                        his[i] = -a;
                    } else {
                        los[i] = 0.0D;
                        his[i] = Math.max(-a, b);
                    }
                }
                case OP_SQUARE -> {
                    int idx = lefts[i];
                    double a = los[idx];
                    double b = his[idx];
                    double sa = a * a, sb = b * b;
                    if (a <= 0.0D && b >= 0.0D) {
                        los[i] = 0.0D;
                        his[i] = Math.max(sa, sb);
                    } else {
                        los[i] = Math.min(sa, sb);
                        his[i] = Math.max(sa, sb);
                    }
                }
                case OP_CUBE -> {
                    int idx = lefts[i];
                    double a = los[idx];
                    double b = his[idx];
                    los[i] = a * a * a;
                    his[i] = b * b * b;
                }
                case OP_HALF_NEG -> {
                    int idx = lefts[i];
                    double a = los[idx];
                    double b = his[idx];
                    los[i] = a > 0.0D ? a : a * 0.5D;
                    his[i] = b > 0.0D ? b : b * 0.5D;
                }
                case OP_QUARTER_NEG -> {
                    int idx = lefts[i];
                    double a = los[idx];
                    double b = his[idx];
                    los[i] = a > 0.0D ? a : a * 0.25D;
                    his[i] = b > 0.0D ? b : b * 0.25D;
                }
                case OP_SQUEEZE -> {
                    int idx = lefts[i];
                    los[i] = squeeze(los[idx]);
                    his[i] = squeeze(his[idx]);
                }
                case OP_INVERT -> {
                    int idx = lefts[i];
                    double a = los[idx];
                    double b = his[idx];
                    if (a > 0.0D || b < 0.0D) {
                        double ia = 1.0D / a;
                        double ib = 1.0D / b;
                        los[i] = Math.min(ia, ib);
                        his[i] = Math.max(ia, ib);
                    } else {
                        los[i] = Double.NEGATIVE_INFINITY;
                        his[i] = Double.POSITIVE_INFINITY;
                    }
                }
                case OP_MOD_BOUND -> {
                    ModBoundState st = (ModBoundState) auxs[i];
                    if (st.quarantined) {
                        los[i] = Double.NEGATIVE_INFINITY;
                        his[i] = Double.POSITIVE_INFINITY;
                    } else {
                        los[i] = pA[i];
                        his[i] = pB[i];
                    }
                }
                default -> {
                    los[i] = Double.NEGATIVE_INFINITY;
                    his[i] = Double.POSITIVE_INFINITY;
                }
            }
        }
    }

    private void rebind(List<?> interps, List<?> flats, Object beardifier) {
        int[] iNodes = this.interpNodeIndices;
        for (int k = 0; k < iNodes.length; k++) {
            int idx = iNodes[k];
            this.aux[idx] = interps.get(this.left[idx]);
        }
        int[] fNodes = this.flatNodeIndices;
        for (int k = 0; k < fNodes.length; k++) {
            int idx = fNodes[k];
            this.aux[idx] = flats.get(this.left[idx]);
        }
        int[] bNodes = this.beardNodeIndices;
        for (int k = 0; k < bNodes.length; k++) {
            rebindBeardifierAt(bNodes[k], beardifier);
        }
        this.lastColX = Integer.MIN_VALUE;
        this.lastColZ = Integer.MIN_VALUE;
        this.boundOwner = null;
    }

    public void ensureBound(Object owner, List<?> interps, List<?> flats, Object beardifier) {
        if (this.boundOwner != owner) {
            rebind(interps, flats, beardifier);
            this.boundOwner = owner;
        }
    }

    private void rebindBeardifierAt(int idx, Object beardifier) {
        if (beardifier == null || ModCompat.isEffectivelyEmptyBeardifier(beardifier)) {
            this.subOp[idx] = BEARD_EMPTY;
            this.aux[idx] = null;
            this.paramA[idx] = 0.0D;
            this.paramB[idx] = 0.0D;
            return;
        }
        boolean moogEnhanced = ModCompat.hasMoogEnhancedBeardifier(beardifier);
        if (beardifier instanceof BeardifierAccess ba && beardifier instanceof DensityFunction df) {
            if (!ba.chunking$hasUnionBox()) {
                this.subOp[idx] = BEARD_UNBOUNDED;
                this.aux[idx] = null;
                this.paramA[idx] = Double.NEGATIVE_INFINITY;
                this.paramB[idx] = Double.POSITIVE_INFINITY;
            } else {
                this.subOp[idx] = BEARD_BOX;
                this.aux[idx] = null;
                int off = this.left[idx];
                int[] bb = this.beardBounds;
                bb[off] = ba.chunking$uMinX();
                bb[off + 1] = ba.chunking$uMinY();
                bb[off + 2] = ba.chunking$uMinZ();
                bb[off + 3] = ba.chunking$uMaxX();
                bb[off + 4] = ba.chunking$uMaxY();
                bb[off + 5] = ba.chunking$uMaxZ();
                if (moogEnhanced) {
                    // Moog's enhanced terrain adaptation can exceed vanilla beardifier min/max inside the structure box.
                    this.paramA[idx] = Double.NEGATIVE_INFINITY;
                    this.paramB[idx] = Double.POSITIVE_INFINITY;
                } else {
                    double mn = df.minValue();
                    double mx = df.maxValue();
                    this.paramA[idx] = mn <= mx ? mn : Double.NEGATIVE_INFINITY;
                    this.paramB[idx] = mn <= mx ? mx : Double.POSITIVE_INFINITY;
                }
            }
            return;
        }
        if (moogEnhanced) {
            this.subOp[idx] = BEARD_UNBOUNDED;
            this.aux[idx] = null;
            this.paramA[idx] = Double.NEGATIVE_INFINITY;
            this.paramB[idx] = Double.POSITIVE_INFINITY;
            return;
        }
        if (beardifier instanceof DensityFunction df) {
            double mn = df.minValue();
            double mx = df.maxValue();
            this.subOp[idx] = BEARD_UNBOUNDED;
            this.aux[idx] = null;
            this.paramA[idx] = mn <= mx ? mn : Double.NEGATIVE_INFINITY;
            this.paramB[idx] = mn <= mx ? mx : Double.POSITIVE_INFINITY;
            return;
        }
        this.subOp[idx] = BEARD_UNBOUNDED;
        this.aux[idx] = null;
        this.paramA[idx] = Double.NEGATIVE_INFINITY;
        this.paramB[idx] = Double.POSITIVE_INFINITY;
    }

    private static DensityFunction unwrapSplineCoord(DensityFunction df) {
        int guard = 0;
        while (guard++ < 32 && df != null) {
            if (df instanceof DensityFunctions.HolderHolder hh) {
                df = hh.function().value();
                continue;
            }
            String cls = df.getClass().getSimpleName();
            if ("CacheOnce".equals(cls) || "CacheAllInCell".equals(cls) || "Marker".equals(cls)) {
                if (df instanceof DensityFunctions.MarkerOrMarked mom) {
                    df = mom.wrapped();
                    continue;
                }
            }
            break;
        }
        return df;
    }

    private static JitSupport.FlatSpline flattenCubicSpline(
            CubicSpline<DensityFunctions.Spline.Coordinate> cs,
            IdentityHashMap<DensityFunction, Integer> coordMap,
            List<DensityFunction> coordFns
    ) {
        if (cs instanceof CubicSpline.Constant<DensityFunctions.Spline.Coordinate> c) {
            return new JitSupport.FlatConst(c.value());
        }
        CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> mp =
                (CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>) cs;
        DensityFunction rawCoordFn = mp.coordinate().function();
        DensityFunction unwrapped = unwrapSplineCoord(rawCoordFn);
        Integer cSlot = coordMap.get(unwrapped);
        if (cSlot == null) {
            cSlot = coordFns.size();
            coordMap.put(unwrapped, cSlot);
            coordFns.add(rawCoordFn);
        }
        List<CubicSpline<DensityFunctions.Spline.Coordinate>> vals = mp.values();
        JitSupport.FlatSpline[] children = new JitSupport.FlatSpline[vals.size()];
        for (int i = 0; i < vals.size(); i++) {
            children[i] = flattenCubicSpline(vals.get(i), coordMap, coordFns);
        }
        return new JitSupport.FlatBranch(cSlot, mp.locations(), children, mp.derivatives());
    }

    public static CellSkipper compile(DensityFunction root, boolean emptyBlender, int cellWidth) {
        if (root == null || !emptyBlender || cellWidth != 4) {
            return null;
        }
        IdentityHashMap<DensityFunction, Integer> memo = new IdentityHashMap<>(64);
        ArrayList<RawNode> rawNodes = new ArrayList<>(48);
        int rootRawIdx = buildRawNode(root, cellWidth, memo, rawNodes, null, null, null);
        if (rootRawIdx < 0 || rawNodes.isEmpty()) {
            return null;
        }
        EstimatorPlan plan = packFlatPlan(rawNodes, rootRawIdx, cellWidth, 0, 0);
        return plan.createThreadInstance();
    }

    private static final java.util.concurrent.ConcurrentMap<Object, EstimatorPlan> PLAN_CACHE =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    /**
     * Compiles using a per-NoiseRouter structural plan cached across chunks,
     * eliminating AST reflection and traversal after the first chunk.
     */
    public static CellSkipper compile(
            Object routerKey,
            List<?> interpolators,
            List<?> flatCaches,
            DensityFunction root,
            boolean blenderEmpty,
            boolean beardifierEmpty,
            int cellWidth) {
        if (!beardifierEmpty) {
            return compile(root, blenderEmpty, cellWidth);
        }
        return compile(routerKey, interpolators, flatCaches, root, blenderEmpty, (Object) null, cellWidth);
    }

    /**
     * Compiles using a per-NoiseRouter structural plan cached across chunks (including structure chunks),
     * binding the chunk's {@code beardifier} dynamically at resolve time without rebuilding the AST.
     */
    public static CellSkipper compile(
            Object routerKey,
            List<?> interpolators,
            List<?> flatCaches,
            DensityFunction root,
            boolean blenderEmpty,
            Object beardifier,
            int cellWidth) {
        if (root == null || !blenderEmpty || cellWidth != 4) {
            return null;
        }
        if (routerKey == null || interpolators == null || flatCaches == null) {
            return compile(root, blenderEmpty, cellWidth);
        }
        EstimatorPlan plan = PLAN_CACHE.get(routerKey);
        if (plan == null) {
            EstimatorPlan built = buildPlan(root, cellWidth, interpolators, flatCaches);
            if (built != null) {
                EstimatorPlan prev = PLAN_CACHE.putIfAbsent(routerKey, built);
                plan = prev != null ? prev : built;
            }
        }
        if (plan != null && plan.cellWidth == cellWidth
                && interpolators.size() >= plan.minInterps
                && flatCaches.size() >= plan.minFlats) {
            return plan.bind(interpolators, flatCaches, beardifier);
        }
        return compile(root, blenderEmpty, cellWidth);
    }

    private static Object invoke0(Object target, String name) {
        MethodHandle mh = ACCESSORS.get(target.getClass()).get(name);
        if (mh == null) {
            return null;
        }
        try {
            return mh.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class EstimatorPlan {
        final int cellWidth;
        final int minInterps;
        final int minFlats;
        final byte[] op;
        final byte[] subOpTemplate;
        final int[] left;
        final int[] right;
        final int[] third;
        final double[] paramATemplate;
        final double[] paramBTemplate;
        final Object[] auxTemplate;
        final int constCount;
        final int yInvEnd;
        final int rootIdx;
        final int[] interpNodeIndices;
        final int[] flatNodeIndices;
        final int[] beardNodeIndices;
        final int[] beardBoundsTemplate;
        final ModBoundState[] modBoundStates;
        private final ThreadLocal<CellSkipper> threadPool =
                ThreadLocal.withInitial(this::createThreadInstance);

        EstimatorPlan(
                int cellWidth,
                int minInterps,
                int minFlats,
                byte[] op,
                byte[] subOpTemplate,
                int[] left,
                int[] right,
                int[] third,
                double[] paramATemplate,
                double[] paramBTemplate,
                Object[] auxTemplate,
                int constCount,
                int yInvEnd,
                int rootIdx,
                int[] interpNodeIndices,
                int[] flatNodeIndices,
                int[] beardNodeIndices,
                int[] beardBoundsTemplate,
                ModBoundState[] modBoundStates
        ) {
            this.cellWidth = cellWidth;
            this.minInterps = minInterps;
            this.minFlats = minFlats;
            this.op = op;
            this.subOpTemplate = subOpTemplate;
            this.left = left;
            this.right = right;
            this.third = third;
            this.paramATemplate = paramATemplate;
            this.paramBTemplate = paramBTemplate;
            this.auxTemplate = auxTemplate;
            this.constCount = constCount;
            this.yInvEnd = yInvEnd;
            this.rootIdx = rootIdx;
            this.interpNodeIndices = interpNodeIndices;
            this.flatNodeIndices = flatNodeIndices;
            this.beardNodeIndices = beardNodeIndices;
            this.beardBoundsTemplate = beardBoundsTemplate;
            this.modBoundStates = modBoundStates;
        }

        CellSkipper createThreadInstance() {
            int n = this.op.length;
            Object[] auxCopy = this.auxTemplate.clone();
            for (int i = 0; i < n; i++) {
                if (this.op[i] == OP_FLAT_SPLINE) {
                    FlatSplineAux orig = (FlatSplineAux) auxCopy[i];
                    auxCopy[i] = new FlatSplineAux(orig.spline, orig.coordIndices, new float[orig.coordIndices.length]);
                }
            }
            int[] beardCopy = this.beardBoundsTemplate.length > 0 ? this.beardBoundsTemplate.clone() : EMPTY_INTS;
            return new CellSkipper(
                    this.op,
                    this.subOpTemplate.clone(),
                    this.left,
                    this.right,
                    this.third,
                    this.paramATemplate.clone(),
                    this.paramBTemplate.clone(),
                    auxCopy,
                    this.constCount,
                    this.yInvEnd,
                    this.rootIdx,
                    this.cellWidth,
                    this.interpNodeIndices,
                    this.flatNodeIndices,
                    this.beardNodeIndices,
                    beardCopy,
                    this.modBoundStates
            );
        }

        CellSkipper bind(List<?> interps, List<?> flats, Object beardifier) {
            CellSkipper est = this.threadPool.get();
            est.rebind(interps, flats, beardifier);
            return est;
        }
    }

    private static boolean hasLeftChild(byte op) {
        return op >= OP_MUL_CONST && op <= OP_INVERT;
    }

    private static boolean hasRightChild(byte op) {
        return (op >= OP_ADD && op <= OP_MUL) || op == OP_RANGE_CHOICE;
    }

    private static EstimatorPlan packFlatPlan(
            ArrayList<RawNode> rawNodes,
            int rootRawIdx,
            int cellWidth,
            int minInterps,
            int minFlats
    ) {
        int n = rawNodes.size();
        int[] remap = new int[n];
        int[] order = new int[n];
        int pos = 0;

        for (int i = 0; i < n; i++) {
            if (rawNodes.get(i).op == OP_CONST) {
                remap[i] = pos;
                order[pos++] = i;
            }
        }
        int constCount = pos;

        for (int i = 0; i < n; i++) {
            RawNode rn = rawNodes.get(i);
            if (rn.op != OP_CONST && rn.yInvariant) {
                remap[i] = pos;
                order[pos++] = i;
            }
        }
        int yInvEnd = pos;

        for (int i = 0; i < n; i++) {
            RawNode rn = rawNodes.get(i);
            if (rn.op != OP_CONST && !rn.yInvariant) {
                remap[i] = pos;
                order[pos++] = i;
            }
        }

        byte[] op = new byte[n];
        byte[] subOp = new byte[n];
        int[] left = new int[n];
        int[] right = new int[n];
        int[] third = new int[n];
        double[] paramA = new double[n];
        double[] paramB = new double[n];
        Object[] aux = new Object[n];

        int interpCount = 0;
        int flatCount = 0;
        int beardCount = 0;

        for (int newIdx = 0; newIdx < n; newIdx++) {
            RawNode rn = rawNodes.get(order[newIdx]);
            byte code = rn.op;
            op[newIdx] = code;
            subOp[newIdx] = rn.subOp;
            left[newIdx] = hasLeftChild(code) ? remap[rn.left] : rn.left;
            right[newIdx] = hasRightChild(code) ? remap[rn.right] : rn.right;
            third[newIdx] = (code == OP_RANGE_CHOICE) ? remap[rn.third] : rn.third;
            paramA[newIdx] = rn.paramA;
            paramB[newIdx] = rn.paramB;
            if (code == OP_FLAT_SPLINE) {
                FlatSplineAux orig = (FlatSplineAux) rn.aux;
                int[] remappedCoords = new int[orig.coordIndices.length];
                for (int k = 0; k < remappedCoords.length; k++) {
                    remappedCoords[k] = remap[orig.coordIndices[k]];
                }
                aux[newIdx] = new FlatSplineAux(orig.spline, remappedCoords, orig.scratch);
            } else {
                aux[newIdx] = rn.aux;
            }
            if (code == OP_INTERP) {
                interpCount++;
            } else if (code == OP_FLAT_CACHE) {
                flatCount++;
            } else if (code == OP_BEARDIFIER) {
                beardCount++;
            }
        }

        int[] interpIndices = new int[interpCount];
        int[] flatIndices = new int[flatCount];
        int[] beardIndices = new int[beardCount];
        int[] beardBoundsTemplate = beardCount > 0 ? new int[beardCount * 6] : EMPTY_INTS;
        ArrayList<ModBoundState> modStateList = null;
        int ii = 0, fi = 0, bi = 0;
        for (int i = 0; i < n; i++) {
            byte code = op[i];
            if (code == OP_INTERP) {
                interpIndices[ii++] = i;
            } else if (code == OP_FLAT_CACHE) {
                flatIndices[fi++] = i;
            } else if (code == OP_BEARDIFIER) {
                int off = bi * 6;
                left[i] = off;
                if (aux[i] instanceof int[] initBox && initBox.length == 6) {
                    System.arraycopy(initBox, 0, beardBoundsTemplate, off, 6);
                    aux[i] = null;
                }
                beardIndices[bi++] = i;
            } else if (code == OP_MOD_BOUND && aux[i] instanceof ModBoundState st) {
                if (modStateList == null) {
                    modStateList = new ArrayList<>(4);
                }
                if (!modStateList.contains(st)) {
                    modStateList.add(st);
                }
            }
        }
        ModBoundState[] modStates = modStateList != null
                ? modStateList.toArray(ModBoundState[]::new)
                : EMPTY_MOD_STATES;

        return new EstimatorPlan(
                cellWidth, minInterps, minFlats,
                op, subOp, left, right, third, paramA, paramB, aux,
                constCount, yInvEnd, remap[rootRawIdx],
                interpIndices, flatIndices, beardIndices, beardBoundsTemplate, modStates
        );
    }

    private static int identityIndexOf(List<?> list, Object target) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static EstimatorPlan buildPlan(
            DensityFunction root,
            int cellWidth,
            List<?> interpolators,
            List<?> flatCaches) {
        IdentityHashMap<DensityFunction, Integer> memo = new IdentityHashMap<>(64);
        ArrayList<RawNode> rawNodes = new ArrayList<>(48);
        int[] maxSlots = new int[2]; // [0] = minInterps, [1] = minFlats
        int rootRawIdx = buildRawNode(root, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
        if (rootRawIdx < 0 || rawNodes.isEmpty()) {
            return null;
        }
        return packFlatPlan(rawNodes, rootRawIdx, cellWidth, maxSlots[0], maxSlots[1]);
    }

    private static int addRaw(
            DensityFunction df,
            RawNode node,
            IdentityHashMap<DensityFunction, Integer> memo,
            ArrayList<RawNode> rawNodes) {
        int idx = rawNodes.size();
        rawNodes.add(node);
        if (df != null) {
            memo.put(df, idx);
        }
        return idx;
    }

    private static byte mapOpCode(String name) {
        return switch (name) {
            case "ABS" -> OP_ABS;
            case "SQUARE" -> OP_SQUARE;
            case "CUBE" -> OP_CUBE;
            case "HALF_NEGATIVE" -> OP_HALF_NEG;
            case "QUARTER_NEGATIVE" -> OP_QUARTER_NEG;
            case "SQUEEZE" -> OP_SQUEEZE;
            case "INVERT" -> OP_INVERT;
            default -> -1;
        };
    }

    private static byte ap2OpCode(DensityFunctions.TwoArgumentSimpleFunction.Type type) {
        return switch (type) {
            case ADD -> OP_ADD;
            case MIN -> OP_MIN;
            case MAX -> OP_MAX;
            case MUL -> OP_MUL;
        };
    }

    private static int buildRawNode(
            DensityFunction df,
            int cellWidth,
            IdentityHashMap<DensityFunction, Integer> memo,
            ArrayList<RawNode> rawNodes,
            List<?> interpolators,
            List<?> flatCaches,
            int[] maxSlots) {
        Integer existing = memo.get(df);
        if (existing != null) {
            return existing;
        }
        boolean planMode = (interpolators != null && flatCaches != null);

        if (df instanceof NoiseInterpolatorAccess interp) {
            if (planMode) {
                int slot = identityIndexOf(interpolators, interp);
                if (slot < 0) {
                    return -1;
                }
                if (slot + 1 > maxSlots[0]) {
                    maxSlots[0] = slot + 1;
                }
                return addRaw(df, new RawNode(OP_INTERP, (byte) 0, slot, 0, 0, 0.0D, 0.0D, null, false), memo, rawNodes);
            }
            return addRaw(df, new RawNode(OP_INTERP, (byte) 0, -1, 0, 0, 0.0D, 0.0D, interp, false), memo, rawNodes);
        }
        if (df instanceof FlatCacheAccess fc) {
            if (planMode) {
                int slot = identityIndexOf(flatCaches, fc);
                if (slot < 0) {
                    return -1;
                }
                if (slot + 1 > maxSlots[1]) {
                    maxSlots[1] = slot + 1;
                }
                return addRaw(df, new RawNode(OP_FLAT_CACHE, (byte) 0, slot, 0, 0, 0.0D, 0.0D, null, true), memo, rawNodes);
            }
            return addRaw(df, new RawNode(OP_FLAT_CACHE, (byte) 0, -1, 0, 0, 0.0D, 0.0D, fc, true), memo, rawNodes);
        }
        if (df instanceof CacheAllInCellAccess cell) {
            int idx = buildRawNode(cell.chunking$cellNoiseFiller(), cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
            if (idx >= 0) {
                memo.put(df, idx);
            }
            return idx;
        }
        if (df instanceof DensityFunctions.Spline sp) {
            try {
                CubicSpline<DensityFunctions.Spline.Coordinate> cs = sp.spline();
                IdentityHashMap<DensityFunction, Integer> coordMap = new IdentityHashMap<>(8);
                List<DensityFunction> coordFns = new ArrayList<>(4);
                JitSupport.FlatSpline flat = flattenCubicSpline(cs, coordMap, coordFns);
                int[] coordIndices = new int[coordFns.size()];
                boolean allYInv = true;
                for (int i = 0; i < coordFns.size(); i++) {
                    int ci = buildRawNode(coordFns.get(i), cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (ci < 0) {
                        return -1;
                    }
                    coordIndices[i] = ci;
                    if (!rawNodes.get(ci).yInvariant) {
                        allYInv = false;
                    }
                }
                FlatSplineAux spAux = new FlatSplineAux(flat, coordIndices, new float[coordIndices.length]);
                return addRaw(df, new RawNode(OP_FLAT_SPLINE, (byte) 0, 0, 0, 0, df.minValue(), df.maxValue(), spAux, allYInv), memo, rawNodes);
            } catch (Throwable ignored) {
            }
        }
        if (ModCompat.isVanillaClass(df.getClass())
                && "MulOrAdd".equals(df.getClass().getSimpleName())) {
            Object typeObj = invoke0(df, "specificType");
            Object inObj = invoke0(df, "input");
            Object argObj = invoke0(df, "argument");
            if (typeObj instanceof Enum<?> en && inObj instanceof DensityFunction inner && argObj instanceof Double arg) {
                int ii = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                if (ii < 0) {
                    return -1;
                }
                byte opCode = "MUL".equals(en.name()) ? OP_MUL_CONST : OP_ADD_CONST;
                return addRaw(df, new RawNode(opCode, (byte) 0, ii, 0, 0, arg, 0.0D, null, rawNodes.get(ii).yInvariant), memo, rawNodes);
            }
        }
        if (df instanceof DensityFunctions.TwoArgumentSimpleFunction two) {
            int ia = buildRawNode(two.argument1(), cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
            if (ia < 0) {
                return -1;
            }
            int ib = buildRawNode(two.argument2(), cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
            if (ib < 0) {
                return -1;
            }
            boolean yInv = rawNodes.get(ia).yInvariant && rawNodes.get(ib).yInvariant;
            return addRaw(df, new RawNode(ap2OpCode(two.type()), (byte) 0, ia, ib, 0, 0.0D, 0.0D, null, yInv), memo, rawNodes);
        }
        if (df instanceof DensityFunctions.HolderHolder hh) {
            int idx = buildRawNode(hh.function().value(), cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
            if (idx >= 0) {
                memo.put(df, idx);
            }
            return idx;
        }
        if (planMode) {
            if (df instanceof DensityFunctions.BeardifierOrMarker
                    || df instanceof BeardifierAccess
                    || ModCompat.isEffectivelyEmptyBeardifier(df)
                    || ModCompat.hasMoogEnhancedBeardifier(df)) {
                return addRaw(df, new RawNode(OP_BEARDIFIER, BEARD_EMPTY, 0, 0, 0, 0.0D, 0.0D, null, false), memo, rawNodes);
            }
        } else {
            if (ModCompat.isEffectivelyEmptyBeardifier(df)) {
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, 0.0D, 0.0D, null, true), memo, rawNodes);
            }
            boolean moogEnhanced = ModCompat.hasMoogEnhancedBeardifier(df);
            if (df instanceof BeardifierAccess ba) {
                if (!ba.chunking$hasUnionBox()) {
                    return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null, true), memo, rawNodes);
                }
                double loVal = moogEnhanced ? Double.NEGATIVE_INFINITY : df.minValue();
                double hiVal = moogEnhanced ? Double.POSITIVE_INFINITY : df.maxValue();
                if (loVal > hiVal) {
                    loVal = Double.NEGATIVE_INFINITY;
                    hiVal = Double.POSITIVE_INFINITY;
                }
                int[] initBox = new int[] {
                        ba.chunking$uMinX(), ba.chunking$uMinY(), ba.chunking$uMinZ(),
                        ba.chunking$uMaxX(), ba.chunking$uMaxY(), ba.chunking$uMaxZ()
                };
                return addRaw(df, new RawNode(OP_BEARDIFIER, BEARD_BOX, 0, 0, 0, loVal, hiVal, initBox, false), memo, rawNodes);
            }
            if (moogEnhanced) {
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null, true), memo, rawNodes);
            }
        }
        if (!ModCompat.isVanillaClass(df.getClass())) {
            DensityFunction unwrapped = NoiseJit.unwrapTransparentModDf(df);
            if (unwrapped != df) {
                int idx = buildRawNode(unwrapped, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                if (idx >= 0) {
                    memo.put(df, idx);
                }
                return idx;
            }
        }
        if (ModCompat.isStatelessModDensityFunction(df)) {
            switch (df.getClass().getName()) {
                case "dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction" -> {
                    if (invoke0(df, "full") instanceof DensityFunction full) {
                        int idx = buildRawNode(full, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                        if (idx >= 0) {
                            memo.put(df, idx);
                        }
                        return idx;
                    }
                }
                case "dev.worldgen.tectonic.worldgen.densityfunction.ConfigConstant" -> {
                    return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, df.minValue(), df.maxValue(), null, true), memo, rawNodes);
                }
                case "dev.worldgen.tectonic.worldgen.densityfunction.Invert" -> {
                    if (invoke0(df, "input") instanceof DensityFunction inner) {
                        int ii = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                        if (ii < 0) {
                            return -1;
                        }
                        return addRaw(df, new RawNode(OP_INVERT, (byte) 0, ii, 0, 0, 0.0D, 0.0D, null, rawNodes.get(ii).yInvariant), memo, rawNodes);
                    }
                }
                case "dev.worldgen.tectonic.worldgen.densityfunction.ConfigClamp" -> {
                    if (invoke0(df, "input") instanceof DensityFunction inner
                            && invoke0(df, "min") instanceof DensityFunction minDf
                            && invoke0(df, "max") instanceof DensityFunction maxDf) {
                        int ii = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                        if (ii < 0) {
                            return -1;
                        }
                        if (minDf.minValue() == minDf.maxValue()
                                && maxDf.minValue() == maxDf.maxValue()
                                && minDf.minValue() <= maxDf.minValue()) {
                            return addRaw(df, new RawNode(OP_CLAMP, (byte) 0, ii, 0, 0, minDf.minValue(), maxDf.minValue(), null, rawNodes.get(ii).yInvariant), memo, rawNodes);
                        }
                        int minIdx = buildRawNode(minDf, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                        int maxIdx = buildRawNode(maxDf, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                        if (minIdx < 0 || maxIdx < 0) {
                            return -1;
                        }
                        boolean innerMaxYInv = rawNodes.get(ii).yInvariant && rawNodes.get(minIdx).yInvariant;
                        int maxNodeIdx = addRaw(null, new RawNode(OP_MAX, (byte) 0, ii, minIdx, 0, 0.0D, 0.0D, null, innerMaxYInv), memo, rawNodes);
                        boolean clampYInv = innerMaxYInv && rawNodes.get(maxIdx).yInvariant;
                        return addRaw(df, new RawNode(OP_MIN, (byte) 0, maxNodeIdx, maxIdx, 0, 0.0D, 0.0D, null, clampYInv), memo, rawNodes);
                    }
                }
                case "dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise" -> {
                    if (invoke0(df, "noise") instanceof DensityFunction.NoiseHolder holder
                            && invoke0(df, "multiplier") instanceof Double mult
                            && invoke0(df, "offset") instanceof Double off) {
                        double bound = Math.abs(holder.maxValue() * mult);
                        return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, -bound + off, bound + off, null, true), memo, rawNodes);
                    }
                }
                default -> {}
            }
        }
        if (ModCompat.isVanillaClass(df.getClass())) {
            String cn = df.getClass().getSimpleName();
            if ("BlendAlpha".equals(cn)) {
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, 1.0D, 1.0D, null, true), memo, rawNodes);
            }
            if ("BlendOffset".equals(cn)) {
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, 0.0D, 0.0D, null, true), memo, rawNodes);
            }
            if ("BeardifierMarker".equals(cn)) {
                if (planMode) {
                    return addRaw(df, new RawNode(OP_BEARDIFIER, BEARD_EMPTY, 0, 0, 0, 0.0D, 0.0D, null, false), memo, rawNodes);
                }
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, 0.0D, 0.0D, null, true), memo, rawNodes);
            }
            if ("Constant".equals(cn) || "Zero".equals(cn)) {
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, df.minValue(), df.maxValue(), null, true), memo, rawNodes);
            }
            if ("CacheOnce".equals(cn) || "Marker".equals(cn) || "BlendDensity".equals(cn)) {
                Object child = invoke0(df, "wrapped");
                if (child instanceof DensityFunction inner) {
                    int idx = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (idx >= 0) {
                        memo.put(df, idx);
                    }
                    return idx;
                }
            }
            if ("Cache2D".equals(cn)) {
                Object child = invoke0(df, "wrapped");
                if (child instanceof DensityFunction inner) {
                    int idx = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (idx >= 0 && rawNodes.get(idx).yInvariant) {
                        memo.put(df, idx);
                        return idx;
                    }
                }
            }
            if ("YClampedGradient".equals(cn)) {
                Object fY = invoke0(df, "fromY");
                Object tY = invoke0(df, "toY");
                Object fV = invoke0(df, "fromValue");
                Object tV = invoke0(df, "toValue");
                if (fY instanceof Integer a && tY instanceof Integer b && fV instanceof Double c && tV instanceof Double d) {
                    return addRaw(df, new RawNode(OP_YGRAD, (byte) 0, a, b, 0, c, d, null, false), memo, rawNodes);
                }
            }
            if ("Mapped".equals(cn)) {
                Object typeObj = invoke0(df, "type");
                Object inObj = invoke0(df, "input");
                if (typeObj instanceof Enum<?> en && inObj instanceof DensityFunction inner) {
                    byte opCode = mapOpCode(en.name());
                    if (opCode >= 0) {
                        int ii = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                        if (ii < 0) {
                            return -1;
                        }
                        return addRaw(df, new RawNode(opCode, (byte) 0, ii, 0, 0, 0.0D, 0.0D, null, rawNodes.get(ii).yInvariant), memo, rawNodes);
                    }
                }
            }
            if ("Clamp".equals(cn)) {
                Object inObj = invoke0(df, "input");
                if (inObj instanceof DensityFunction inner) {
                    int ii = buildRawNode(inner, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (ii < 0) {
                        return -1;
                    }
                    return addRaw(df, new RawNode(OP_CLAMP, (byte) 0, ii, 0, 0, df.minValue(), df.maxValue(), null, rawNodes.get(ii).yInvariant), memo, rawNodes);
                }
            }
            if ("RangeChoice".equals(cn)) {
                Object inObj = invoke0(df, "input");
                Object mInc = invoke0(df, "minInclusive");
                Object mExc = invoke0(df, "maxExclusive");
                Object wIn = invoke0(df, "whenInRange");
                Object wOut = invoke0(df, "whenOutOfRange");
                if (inObj instanceof DensityFunction inp && mInc instanceof Double mn && mExc instanceof Double mx
                        && wIn instanceof DensityFunction wi && wOut instanceof DensityFunction wo) {
                    int ii = buildRawNode(inp, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (ii < 0) {
                        return -1;
                    }
                    int iWi = buildRawNode(wi, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (iWi < 0) {
                        return -1;
                    }
                    int iWo = buildRawNode(wo, cellWidth, memo, rawNodes, interpolators, flatCaches, maxSlots);
                    if (iWo < 0) {
                        return -1;
                    }
                    boolean yInv = rawNodes.get(ii).yInvariant && rawNodes.get(iWi).yInvariant && rawNodes.get(iWo).yInvariant;
                    return addRaw(df, new RawNode(OP_RANGE_CHOICE, (byte) 0, iWi, iWo, ii, mn, mx, null, yInv), memo, rawNodes);
                }
            }
            double mn = df.minValue();
            double mx = df.maxValue();
            if (mn <= mx) {
                return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, mn, mx, null, true), memo, rawNodes);
            }
        } else {
            double mn = df.minValue();
            double mx = df.maxValue();
            if (Double.isFinite(mn) && Double.isFinite(mx) && mn <= mx) {
                ModBoundState state = MOD_BOUND_STATES.get(df.getClass());
                if (!state.quarantined) {
                    if (!state.verified && state.staticProbed.compareAndSet(false, true)) {
                        probeModDfStatic(df, mn, mx, state);
                    }
                    if (!state.quarantined) {
                        return addRaw(df, new RawNode(OP_MOD_BOUND, (byte) 0, 0, 0, 0, mn, mx, state, true), memo, rawNodes);
                    }
                }
            }
        }
        return addRaw(df, new RawNode(OP_CONST, (byte) 0, 0, 0, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, null, true), memo, rawNodes);
    }
}
