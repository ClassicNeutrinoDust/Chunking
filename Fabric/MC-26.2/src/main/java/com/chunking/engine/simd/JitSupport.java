package com.chunking.engine.simd;

import com.chunking.engine.mixin.accessor.NoiseChunkAccessor;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.chunking.engine.access.AquiferAccess;
import com.chunking.engine.access.BlendedNoiseAccess;
import com.chunking.engine.access.Cache2DAccess;
import com.chunking.engine.access.CacheAllInCellAccess;
import com.chunking.engine.access.FlatCacheAccess;
import com.chunking.engine.access.NoiseInterpolatorAccess;
import com.chunking.engine.access.NormalNoiseAccess;

/**
 * Runtime support helpers invoked directly by Java 25 {@code ClassFile}-generated hidden density kernels.
 */
public final class JitSupport {
    private static final ThreadLocal<MutableContext> CTX_TL = ThreadLocal.withInitial(MutableContext::new);
    private static final ThreadLocal<ArrayDeque<double[][]>> SLICE_POOL_OW_TL =
            ThreadLocal.withInitial(() -> new ArrayDeque<>(64));
    private static final ThreadLocal<ArrayDeque<double[][]>> SLICE_POOL_NETHER_TL =
            ThreadLocal.withInitial(() -> new ArrayDeque<>(64));
    private static final ThreadLocal<ArrayDeque<double[][]>> SLICE_POOL_END_TL =
            ThreadLocal.withInitial(() -> new ArrayDeque<>(64));
    private static final ThreadLocal<CustomSlicePool> CUSTOM_SLICE_POOL_TL =
            ThreadLocal.withInitial(CustomSlicePool::new);

    private static final class CustomSlicePool {
        static final int MAX_SLOTS = 16;
        final int[] dimXZs = new int[MAX_SLOTS];
        final int[] dimYs = new int[MAX_SLOTS];
        @SuppressWarnings("unchecked")
        final ArrayDeque<double[][]>[] pools = new ArrayDeque[MAX_SLOTS];
        int count = 0;
        int nextEvict = 0;

        double[][] borrow(int dimXZ, int dimY) {
            for (int i = 0; i < this.count; i++) {
                if (this.dimXZs[i] == dimXZ && this.dimYs[i] == dimY) {
                    return this.pools[i].pollLast();
                }
            }
            return null;
        }

        void recycle(int dimXZ, int dimY, double[][] slice) {
            if (dimXZ <= 0 || dimXZ > 17 || dimY <= 0 || dimY > 1025) {
                return;
            }
            for (int i = 0; i < this.count; i++) {
                if (this.dimXZs[i] == dimXZ && this.dimYs[i] == dimY) {
                    ArrayDeque<double[][]> q = this.pools[i];
                    if (q.size() < 64) {
                        q.addLast(slice);
                    }
                    return;
                }
            }
            int idx;
            if (this.count < MAX_SLOTS) {
                idx = this.count++;
            } else {
                idx = this.nextEvict;
                this.nextEvict = (this.nextEvict + 1) & (MAX_SLOTS - 1);
            }
            this.dimXZs[idx] = dimXZ;
            this.dimYs[idx] = dimY;
            ArrayDeque<double[][]> q = this.pools[idx];
            if (q == null) {
                q = new ArrayDeque<>(64);
                this.pools[idx] = q;
            } else {
                q.clear();
            }
            q.addLast(slice);
        }
    }
    private static final class ScratchBanks {
        int depth;
        @SuppressWarnings("unchecked")
        ArrayList<double[]>[] banks = new ArrayList[4];
    }

    private static final ThreadLocal<ScratchBanks> COL_SCRATCH_TL =
            ThreadLocal.withInitial(ScratchBanks::new);

    private JitSupport() {}

    public static void enterJitScratchFrame() {
        COL_SCRATCH_TL.get().depth++;
    }

    public static void exitJitScratchFrame() {
        ScratchBanks sb = COL_SCRATCH_TL.get();
        if (sb.depth > 0) {
            sb.depth--;
        }
    }

    public static double[] borrowColScratch(int slot, int count) {
        ScratchBanks sb = COL_SCRATCH_TL.get();
        int idx = Math.max(0, sb.depth - 1);
        if (idx >= sb.banks.length) {
            sb.banks = java.util.Arrays.copyOf(sb.banks, idx + 2);
        }
        ArrayList<double[]> list = sb.banks[idx];
        if (list == null) {
            list = new ArrayList<>(24);
            sb.banks[idx] = list;
        }
        while (list.size() <= slot) {
            list.add(new double[Math.max(128, count)]);
        }
        double[] buf = list.get(slot);
        if (buf.length < count) {
            buf = new double[count];
            list.set(slot, buf);
        }
        return buf;
    }

    public static float[] borrowSplineFloatScratch(int minLen) {
        MutableContext ctx = CTX_TL.get();
        float[] buf = ctx.splineFloatScratch;
        if (buf.length < minLen) {
            buf = new float[minLen];
            ctx.splineFloatScratch = buf;
        }
        return buf;
    }

    public static void sampleNormalNoiseColumn(
            NormalNoise noise, double xFixed, int[] by, double yScale, double zFixed, double[] out, int count
    ) {
        ((NormalNoiseAccess) noise).chunking$sampleColumnFast(xFixed, by, yScale, zFixed, out, count);
    }

    public static void sampleNormalNoiseBatchInt(
            NormalNoise noise, int[] bx, int[] by, int[] bz, double xzScale, double yScale, double[] out, int count
    ) {
        ((NormalNoiseAccess) noise).chunking$sampleBatch3DInt(bx, by, bz, xzScale, yScale, out, count);
    }

    public static void sampleShiftBatchInt(
            NormalNoise noise, int[] bx, int[] by, int[] bz, double[] out, int count
    ) {
        ((NormalNoiseAccess) noise).chunking$sampleBatch3DInt(bx, by, bz, 0.25D, 0.25D, out, count);
        for (int i = 0; i < count; i++) {
            out[i] *= 4.0D;
        }
    }

    public static void sampleShiftColumn(
            NormalNoise noise, double xFixed, int[] by, double zFixed, double[] out, int count
    ) {
        ((NormalNoiseAccess) noise).chunking$sampleColumnFast(xFixed, by, 0.25D, zFixed, out, count);
        for (int i = 0; i < count; i++) {
            out[i] *= 4.0D;
        }
    }

    public static void sampleNormalNoiseColumnRange(
            NormalNoise noise, double xFixed, int[] by, int startIdx, double yScale, double zFixed, double[] out, int totalCount
    ) {
        ((NormalNoiseAccess) noise).chunking$sampleColumnRange(xFixed, by, startIdx, yScale, zFixed, out, totalCount);
    }

    public static void sampleShiftColumnRange(
            NormalNoise noise, double xFixed, int[] by, int startIdx, double zFixed, double[] out, int totalCount
    ) {
        ((NormalNoiseAccess) noise).chunking$sampleColumnRange(xFixed, by, startIdx, 0.25D, zFixed, out, totalCount);
        for (int i = startIdx; i < totalCount; i++) {
            out[i] *= 4.0D;
        }
    }

    public static void sampleBlendedNoiseColumnRange(
            BlendedNoise noise, int bx, int[] by, int startIdx, int bz, double[] out, int totalCount
    ) {
        ((BlendedNoiseAccess) noise).chunking$evalColumnRange(bx, by, startIdx, bz, out, totalCount);
    }

    public static void sampleBlendedNoiseColumn(
            BlendedNoise noise, int bx, int[] by, int bz, double[] out, int count
    ) {
        ((BlendedNoiseAccess) noise).chunking$evalColumnFast(bx, by, bz, out, count);
    }

    public static double[][] borrowInterpolatorSlice(int dimXZ, int dimY) {
        if (dimXZ == 5 && dimY == 49) {
            return SLICE_POOL_OW_TL.get().pollLast();
        }
        if (dimXZ == 5 && dimY == 17) {
            return SLICE_POOL_NETHER_TL.get().pollLast();
        }
        if (dimXZ == 3 && dimY == 65) {
            return SLICE_POOL_END_TL.get().pollLast();
        }
        return CUSTOM_SLICE_POOL_TL.get().borrow(dimXZ, dimY);
    }

    public static void recycleInterpolatorSlice(double[][] slice) {
        if (slice == null || slice.length == 0 || slice[0] == null) {
            return;
        }
        int dimXZ = slice.length;
        int dimY = slice[0].length;
        ThreadLocal<ArrayDeque<double[][]>> tl;
        if (dimXZ == 5 && dimY == 49) {
            tl = SLICE_POOL_OW_TL;
        } else if (dimXZ == 5 && dimY == 17) {
            tl = SLICE_POOL_NETHER_TL;
        } else if (dimXZ == 3 && dimY == 65) {
            tl = SLICE_POOL_END_TL;
        } else {
            CUSTOM_SLICE_POOL_TL.get().recycle(dimXZ, dimY, slice);
            return;
        }
        ArrayDeque<double[][]> pool = tl.get();
        if (pool.size() < 64) {
            pool.addLast(slice);
        }
    }

    /**
     * Compiled native-JIT batch evaluator interface.
     */
    @FunctionalInterface
    public interface Kernel {
        void evalBatch(
                int[] bx,
                int[] by,
                int[] bz,
                double[] out,
                int count,
                Object[] bindings,
                NoiseChunk noiseChunk,
                boolean constantYColumn
        );
    }

    public static final class MutableContext implements DensityFunction.FunctionContext {
        public int x;
        public int y;
        public int z;
        float[] splineFloatScratch = new float[16];

        public MutableContext set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public int blockX() {
            return this.x;
        }

        @Override
        public int blockY() {
            return this.y;
        }

        @Override
        public int blockZ() {
            return this.z;
        }
    }

    public static MutableContext borrowContext(int bx, int by, int bz) {
        return CTX_TL.get().set(bx, by, bz);
    }

    public static double sampleNormalNoise(NormalNoise noise, double x, double y, double z) {
        return ((NormalNoiseAccess) noise).chunking$sampleNormalFast(x, y, z);
    }

    public static double sampleBlendedNoise(BlendedNoise noise, int bx, int by, int bz) {
        return ((BlendedNoiseAccess) noise).chunking$computeFast(bx, by, bz);
    }

    public static double evalFallback(DensityFunction fn, int bx, int by, int bz) {
        MutableContext ctx = CTX_TL.get();
        int px = ctx.x, py = ctx.y, pz = ctx.z;
        ctx.set(bx, by, bz);
        try {
            return fn.compute(ctx);
        } finally {
            ctx.set(px, py, pz);
        }
    }

    public static double sampleFlatCache(Object accessObj, int bx, int bz) {
        return ((FlatCacheAccess) accessObj).chunking$sampleQuartFast(bx, bz);
    }

    public static double sampleCache2D(Object accessObj, int bx, int bz) {
        return ((Cache2DAccess) accessObj).chunking$sample2DFast(bx, bz);
    }

    public static double sampleInterpolatorPoint(Object accessObj, NoiseChunk noiseChunk, int bx, int by, int bz) {
        NoiseInterpolatorAccess interp = (NoiseInterpolatorAccess) accessObj;
        if (noiseChunk != null) {
            NoiseChunkAccessor acc = (NoiseChunkAccessor) noiseChunk;
            if (acc.chunking$isInterpolating() && acc.chunking$isFillingCell()) {
                int cw = acc.chunking$getCellWidth();
                int ch = acc.chunking$getCellHeight();
                double tx = (double) (bx - acc.chunking$getCellStartBlockX()) / (double) cw;
                double ty = (double) (by - acc.chunking$getCellStartBlockY()) / (double) ch;
                double tz = (double) (bz - acc.chunking$getCellStartBlockZ()) / (double) cw;
                double xz00 = Mth.lerp(ty, interp.chunking$getNoise000(), interp.chunking$getNoise010());
                double xz10 = Mth.lerp(ty, interp.chunking$getNoise100(), interp.chunking$getNoise110());
                double xz01 = Mth.lerp(ty, interp.chunking$getNoise001(), interp.chunking$getNoise011());
                double xz11 = Mth.lerp(ty, interp.chunking$getNoise101(), interp.chunking$getNoise111());
                double z0 = Mth.lerp(tx, xz00, xz10);
                double z1 = Mth.lerp(tx, xz01, xz11);
                return Mth.lerp(tz, z0, z1);
            }
        }
        return interp.chunking$evalUnaligned(bx, by, bz);
    }

    public static boolean isBlenderEmpty(NoiseChunk noiseChunk) {
        if (noiseChunk == null) {
            return true;
        }
        Blender blender = ((NoiseChunkAccessor) noiseChunk).chunking$getBlender();
        return blender == null || blender.isEmpty();
    }

    public static double blendDensity(NoiseChunk noiseChunk, int bx, int by, int bz, double val) {
        if (noiseChunk == null) {
            return val;
        }
        Blender blender = ((NoiseChunkAccessor) noiseChunk).chunking$getBlender();
        if (blender == null || blender.isEmpty()) {
            return val;
        }
        MutableContext ctx = CTX_TL.get();
        int px = ctx.x, py = ctx.y, pz = ctx.z;
        ctx.set(bx, by, bz);
        try {
            return blender.blendDensity(ctx, val);
        } finally {
            ctx.set(px, py, pz);
        }
    }

    public static double blendAlpha(NoiseChunk noiseChunk, int bx, int by, int bz, DensityFunction fn) {
        if (noiseChunk == null || isBlenderEmpty(noiseChunk)) {
            return 1.0D;
        }
        MutableContext ctx = CTX_TL.get();
        int px = ctx.x, py = ctx.y, pz = ctx.z;
        ctx.set(bx, by, bz);
        try {
            return fn.compute(ctx);
        } finally {
            ctx.set(px, py, pz);
        }
    }

    public static double blendOffset(NoiseChunk noiseChunk, int bx, int by, int bz, DensityFunction fn) {
        if (noiseChunk == null || isBlenderEmpty(noiseChunk)) {
            return 0.0D;
        }
        MutableContext ctx = CTX_TL.get();
        int px = ctx.x, py = ctx.y, pz = ctx.z;
        ctx.set(bx, by, bz);
        try {
            return fn.compute(ctx);
        } finally {
            ctx.set(px, py, pz);
        }
    }

    public static boolean canUseCellCorners(NoiseChunk noiseChunk) {
        if (noiseChunk == null) {
            return false;
        }
        NoiseChunkAccessor acc = (NoiseChunkAccessor) noiseChunk;
        return acc.chunking$isInterpolating() && acc.chunking$isFillingCell();
    }

    public static int getCellStartX(NoiseChunk nc) {
        return ((NoiseChunkAccessor) nc).chunking$getCellStartBlockX();
    }

    public static int getCellStartY(NoiseChunk nc) {
        return ((NoiseChunkAccessor) nc).chunking$getCellStartBlockY();
    }

    public static int getCellStartZ(NoiseChunk nc) {
        return ((NoiseChunkAccessor) nc).chunking$getCellStartBlockZ();
    }

    public static double getInvCellWidth(NoiseChunk nc) {
        return 1.0D / (double) ((NoiseChunkAccessor) nc).chunking$getCellWidth();
    }

    public static double getInvCellHeight(NoiseChunk nc) {
        return 1.0D / (double) ((NoiseChunkAccessor) nc).chunking$getCellHeight();
    }

    public static double sampleCornerLerp(
            double tx,
            double ty,
            double tz,
            double n000,
            double n100,
            double n010,
            double n110,
            double n001,
            double n101,
            double n011,
            double n111
    ) {
        double xz00 = n000 + ty * (n010 - n000);
        double xz10 = n100 + ty * (n110 - n100);
        double xz01 = n001 + ty * (n011 - n001);
        double xz11 = n101 + ty * (n111 - n101);
        double z0 = xz00 + tx * (xz10 - xz00);
        double z1 = xz01 + tx * (xz11 - xz01);
        return z0 + tz * (z1 - z0);
    }

    /**
     * Separable Y -> X -> Z trilinear cell pre-evaluator producing bit-exact IEEE-754 output
     * identical to {@link #sampleCornerLerp} with 4x fewer floating-point lerps.
     */
    public static double[] fillCellInterpolator(
            Object accessObj,
            NoiseChunk nc,
            int interpOrdinal,
            int count
    ) {
        if (nc == null || !(accessObj instanceof NoiseInterpolatorAccess interp)) {
            return null;
        }
        NoiseChunkAccessor acc = (NoiseChunkAccessor) nc;
        int cw = acc.chunking$getCellWidth();
        int ch = acc.chunking$getCellHeight();
        if (cw <= 0 || ch <= 0 || cw * cw * ch != count) {
            return null;
        }
        double n000 = interp.chunking$getNoise000();
        double n100 = interp.chunking$getNoise100();
        double n010 = interp.chunking$getNoise010();
        double n110 = interp.chunking$getNoise110();
        double n001 = interp.chunking$getNoise001();
        double n101 = interp.chunking$getNoise101();
        double n011 = interp.chunking$getNoise011();
        double n111 = interp.chunking$getNoise111();
        double d00 = n010 - n000;
        double d10 = n110 - n100;
        double d01 = n011 - n001;
        double d11 = n111 - n101;
        double invCw = 1.0D / (double) cw;
        double invCh = 1.0D / (double) ch;
        double[] out = borrowColScratch(12 + interpOrdinal, count);
        int idx = 0;
        for (int y = ch - 1; y >= 0; y--) {
            double ty = (double) y * invCh;
            double xz00 = n000 + ty * d00;
            double xz10 = n100 + ty * d10;
            double xz01 = n001 + ty * d01;
            double xz11 = n101 + ty * d11;
            double dxz0 = xz10 - xz00;
            double dxz1 = xz11 - xz01;
            for (int x = 0; x < cw; x++) {
                double tx = (double) x * invCw;
                double z0 = xz00 + tx * dxz0;
                double z1 = xz01 + tx * dxz1;
                double dz = z1 - z0;
                for (int z = 0; z < cw; z++) {
                    double tz = (double) z * invCw;
                    out[idx++] = z0 + tz * dz;
                }
            }
        }
        return out;
    }

    public sealed interface FlatSpline permits FlatConst, FlatBranch {}

    public record FlatConst(float value) implements FlatSpline {}

    public record FlatBranch(int coordSlot, float[] locs, FlatSpline[] values, float[] derivs) implements FlatSpline {}

    public static double evalFlatSpline(FlatSpline spline, float[] coords) {
        return evalFlatSplineFloat(spline, coords);
    }

    private static float evalFlatSplineFloat(FlatSpline spline, float[] coords) {
        if (spline instanceof FlatConst fc) {
            return fc.value;
        }
        FlatBranch b = (FlatBranch) spline;
        float[] locs = b.locs;
        float c = coords[b.coordSlot];
        int i = findSplineInterval(locs, c);
        int last = locs.length - 1;
        if (i < 0) {
            return linearExtend(c, locs, evalFlatSplineFloat(b.values[0], coords), b.derivs, 0);
        }
        if (i == last) {
            return linearExtend(c, locs, evalFlatSplineFloat(b.values[last], coords), b.derivs, last);
        }
        float l0 = locs[i];
        float l1 = locs[i + 1];
        float dx = l1 - l0;
        float t = (c - l0) / dx;
        float v0 = evalFlatSplineFloat(b.values[i], coords);
        float v1 = evalFlatSplineFloat(b.values[i + 1], coords);
        float diff = v1 - v0;
        float a = b.derivs[i] * dx - diff;
        float bm = -b.derivs[i + 1] * dx + diff;
        return Mth.lerp(t, v0, v1) + t * (1.0F - t) * Mth.lerp(t, a, bm);
    }

    private static int findSplineInterval(float[] locs, float c) {
        int lo = 0;
        int hi = locs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c < locs[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo - 1;
    }

    private static float linearExtend(float c, float[] locs, float val, float[] derivs, int idx) {
        float d = derivs[idx];
        return d == 0.0F ? val : val + d * (c - locs[idx]);
    }

    private record LastOreMask(java.lang.ref.WeakReference<RandomState> stateRef, boolean[] mask) {}

    private static final Map<RandomState, boolean[]> ORE_INTERPOLATOR_MASK_CACHE =
            new WeakHashMap<>();
    private static volatile LastOreMask LAST_ORE_MASK;

    public static void classifyAndMarkOreInterpolators(
            RandomState randomState,
            List<? extends Object> interpolators,
            Object aquifer,
            DensityFunction preliminarySurfaceLevel,
            DensityFunction fullNoiseDensity
    ) {
        if (randomState == null || interpolators == null || interpolators.isEmpty()) {
            return;
        }
        if (!(aquifer instanceof AquiferAccess)) {
            return;
        }
        int size = interpolators.size();
        LastOreMask last = LAST_ORE_MASK;
        boolean[] mask = (last != null && last.stateRef.get() == randomState) ? last.mask : null;
        if (mask == null || mask.length != size) {
            synchronized (ORE_INTERPOLATOR_MASK_CACHE) {
                mask = ORE_INTERPOLATOR_MASK_CACHE.get(randomState);
                if (mask == null || mask.length != size) {
                    mask = computeOreInterpolatorMask(interpolators, aquifer, preliminarySurfaceLevel, fullNoiseDensity);
                    ORE_INTERPOLATOR_MASK_CACHE.put(randomState, mask);
                }
                LAST_ORE_MASK = new LastOreMask(new java.lang.ref.WeakReference<>(randomState), mask);
            }
        }
        for (int i = 0; i < size; i++) {
            if (interpolators.get(i) instanceof NoiseInterpolatorAccess acc) {
                acc.chunking$setOreVeinOnly(mask[i]);
            }
        }
    }

    private static final Field[] NO_RANGE_CHOICE_FIELDS = new Field[0];
    private static final ClassValue<Field[]> RANGE_CHOICE_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            Field fOut = null;
            Field fIn = null;
            for (Field f : type.getDeclaredFields()) {
                if (f.getName().equals("whenOutOfRange")) {
                    f.setAccessible(true);
                    fOut = f;
                } else if (f.getName().equals("whenInRange")) {
                    f.setAccessible(true);
                    fIn = f;
                }
            }
            return (fOut != null && fIn != null) ? new Field[] { fOut, fIn } : NO_RANGE_CHOICE_FIELDS;
        }
    };

    public static NoiseInterpolatorAccess findExactOreToggleInterpolator(DensityFunction fn) {
        DensityFunction cur = fn;
        for (int depth = 0; depth < 8 && cur != null; depth++) {
            if (cur instanceof NoiseInterpolatorAccess interp) {
                return interp;
            }
            if (cur instanceof DensityFunctions.MarkerOrMarked marker) {
                cur = marker.wrapped();
                continue;
            }
            if (cur.getClass().getSimpleName().equals("RangeChoice")) {
                try {
                    Field[] fields = RANGE_CHOICE_FIELDS.get(cur.getClass());
                    if (fields.length == 2) {
                        DensityFunction outFn = (DensityFunction) fields[0].get(cur);
                        if (outFn != null && outFn.minValue() > -0.4D && outFn.maxValue() < 0.4D) {
                            cur = (DensityFunction) fields[1].get(cur);
                            continue;
                        }
                    }
                } catch (Throwable ignored) {
                    return null;
                }
            }
            break;
        }
        return null;
    }

    private static final java.util.concurrent.ConcurrentMap<Object, byte[]> INTERP_ROLE_CACHE =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    public static void markUnusedInterpolators(
            NoiseRouter routerKey,
            List<? extends Object> interpolators,
            Object aquifer,
            DensityFunction preliminarySurfaceLevel,
            DensityFunction fullNoiseDensity,
            DensityFunction veinToggle,
            DensityFunction veinRidged,
            DensityFunction veinGap
    ) {
        if (interpolators == null || interpolators.isEmpty()) {
            return;
        }
        int size = interpolators.size();
        byte[] roles = routerKey != null ? INTERP_ROLE_CACHE.get(routerKey) : null;
        if (roles == null || roles.length != size) {
            IdentityHashMap<Object, Boolean> vNonOre = new IdentityHashMap<>(128);
            IdentityHashMap<Object, Boolean> sNonOre = new IdentityHashMap<>(size);
            collectInterpolators(preliminarySurfaceLevel, vNonOre, sNonOre);
            collectInterpolators(fullNoiseDensity, vNonOre, sNonOre);
            collectInterpolators(aquifer, vNonOre, sNonOre);

            IdentityHashMap<Object, Boolean> vToggle = new IdentityHashMap<>(64);
            IdentityHashMap<Object, Boolean> sToggle = new IdentityHashMap<>(size);
            collectInterpolators(veinToggle, vToggle, sToggle);

            IdentityHashMap<Object, Boolean> vExtras = new IdentityHashMap<>(64);
            IdentityHashMap<Object, Boolean> sExtras = new IdentityHashMap<>(size);
            collectInterpolators(veinRidged, vExtras, sExtras);
            collectInterpolators(veinGap, vExtras, sExtras);

            roles = new byte[size];
            for (int i = 0; i < size; i++) {
                Object ip = interpolators.get(i);
                if (sNonOre.containsKey(ip) || sToggle.containsKey(ip)) {
                    roles[i] = 0; // eager fill (nonOre or veinToggle)
                } else if (sExtras.containsKey(ip)) {
                    roles[i] = 2; // defer ore extras (veinRidged / veinGap)
                } else {
                    roles[i] = 1; // unused in fillFromNoise
                }
            }
            if (routerKey != null) {
                INTERP_ROLE_CACHE.putIfAbsent(routerKey, roles);
            }
        }
        for (int i = 0; i < size; i++) {
            if (interpolators.get(i) instanceof NoiseInterpolatorAccess acc) {
                byte role = roles[i];
                if (role == 1) {
                    acc.chunking$setUnusedInNoiseFill(true);
                } else if (role == 2) {
                    acc.chunking$setDeferOreExtras(true);
                }
            }
        }
    }

    private static boolean[] computeOreInterpolatorMask(
            List<? extends Object> interpolators,
            Object aquifer,
            DensityFunction preliminarySurfaceLevel,
            DensityFunction fullNoiseDensity
    ) {
        int size = interpolators.size();
        boolean[] mask = new boolean[size];
        IdentityHashMap<Object, Boolean> nonOreVisited = new IdentityHashMap<>(96);
        IdentityHashMap<Object, Boolean> nonOreInterps = new IdentityHashMap<>(size);
        collectInterpolators(preliminarySurfaceLevel, nonOreVisited, nonOreInterps);
        collectInterpolators(fullNoiseDensity, nonOreVisited, nonOreInterps);
        collectInterpolators(aquifer, nonOreVisited, nonOreInterps);

        for (int i = 0; i < size; i++) {
            Object interp = interpolators.get(i);
            mask[i] = !nonOreInterps.containsKey(interp);
        }
        return mask;
    }

    private static final ClassValue<Field[]> GRAPH_CHILD_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> list = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive()
                            || ft == NoiseChunk.class
                            || net.minecraft.world.level.LevelAccessor.class.isAssignableFrom(ft)
                            || net.minecraft.core.Registry.class.isAssignableFrom(ft)
                            || net.minecraft.world.level.chunk.ChunkAccess.class.isAssignableFrom(ft)
                            || net.minecraft.world.level.block.state.BlockState.class.isAssignableFrom(ft)
                            || net.minecraft.world.level.biome.Biome.class.isAssignableFrom(ft)) {
                        continue;
                    }
                    if (DensityFunction.class.isAssignableFrom(ft)
                            || Holder.class.isAssignableFrom(ft)
                            || CubicSpline.class.isAssignableFrom(ft)
                            || Object.class == ft
                            || Iterable.class.isAssignableFrom(ft)
                            || Map.class.isAssignableFrom(ft)
                            || java.util.Optional.class.isAssignableFrom(ft)
                            || (ft.isArray() && !ft.getComponentType().isPrimitive())
                            || ft.getName().contains("Coordinate")
                            || ft.getName().contains("ToFloatFunction")) {
                        f.setAccessible(true);
                        list.add(f);
                    }
                }
            }
            return list.toArray(Field[]::new);
        }
    };

    private static boolean isTraversableChild(Object obj) {
        return obj != null
                && !(obj instanceof NoiseChunk)
                && (obj instanceof DensityFunction
                || obj instanceof Holder<?>
                || obj instanceof CubicSpline<?>
                || obj instanceof Object[]
                || obj instanceof Iterable<?>
                || obj instanceof Map<?, ?>
                || obj instanceof java.util.Optional<?>
                || obj.getClass().getName().contains("Coordinate"));
    }

    private static void collectInterpolators(
            Object root,
            IdentityHashMap<Object, Boolean> visited,
            IdentityHashMap<Object, Boolean> foundInterps
    ) {
        if (root == null) {
            return;
        }
        ArrayDeque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Object cur = queue.poll();
            if (cur == null || visited.put(cur, Boolean.TRUE) != null) {
                continue;
            }
            if (cur instanceof NoiseInterpolatorAccess) {
                foundInterps.put(cur, Boolean.TRUE);
            }
            if (cur instanceof Object[] arr) {
                for (Object elem : arr) {
                    if (isTraversableChild(elem) && !visited.containsKey(elem)) {
                        queue.add(elem);
                    }
                }
                continue;
            }
            if (cur instanceof Iterable<?> iter) {
                for (Object elem : iter) {
                    if (isTraversableChild(elem) && !visited.containsKey(elem)) {
                        queue.add(elem);
                    }
                }
                continue;
            }
            if (cur instanceof Map<?, ?> map) {
                for (Object val : map.values()) {
                    if (isTraversableChild(val) && !visited.containsKey(val)) {
                        queue.add(val);
                    }
                }
                continue;
            }
            if (cur instanceof java.util.Optional<?> opt) {
                Object val = opt.orElse(null);
                if (isTraversableChild(val) && !visited.containsKey(val)) {
                    queue.add(val);
                }
                continue;
            }
            if (cur instanceof Holder<?> h) {
                Object val = h.value();
                if (val != null && !visited.containsKey(val)) {
                    queue.add(val);
                }
                continue;
            }
            if (cur instanceof CacheAllInCellAccess cellAcc) {
                DensityFunction inner = cellAcc.chunking$cellNoiseFiller();
                if (inner != null && !visited.containsKey(inner)) {
                    queue.add(inner);
                }
            } else if (cur instanceof FlatCacheAccess flatAcc) {
                DensityFunction inner = flatAcc.chunking$flatNoiseFiller();
                if (inner != null && !visited.containsKey(inner)) {
                    queue.add(inner);
                }
            } else if (cur instanceof DensityFunctions.MarkerOrMarked marker) {
                DensityFunction inner = marker.wrapped();
                if (inner != null && inner != cur && !visited.containsKey(inner)) {
                    queue.add(inner);
                }
            }
            for (Field f : GRAPH_CHILD_FIELDS.get(cur.getClass())) {
                try {
                    Object child = f.get(cur);
                    if (isTraversableChild(child) && !visited.containsKey(child)) {
                        queue.add(child);
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
    }
}
