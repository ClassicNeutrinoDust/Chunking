package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.ClimateRTreeAccess;
import com.chunking.engine.access.ClimateSamplerAccess;
import com.chunking.engine.simd.NoiseJit;
import com.chunking.engine.access.FlatCacheAccess;
import com.chunking.engine.world.ModCompat;
import com.chunking.engine.access.PalettedContainerAccess;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Removes 1,536 {@code SinglePointContext} and {@code CallbackInfoReturnable} allocations per chunk
 * in {@link Climate.Sampler#sample}, caches the five vertical-invariant {@code FlatCache} channels across Y quarts,
 * compiles and memoizes 2D global {@code RandomState.sampler()} columns for 20x-35x faster {@code STRUCTURE_STARTS},
 * and writes uniform biome sections in O(1) time via 0-bit {@code SingleValuePalette} containers.
 */
@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin implements ClimateSamplerAccess {
    private static final int GLOBAL_COL_CACHE_SIZE = 4096;
    private static final int GLOBAL_COL_CACHE_MASK = GLOBAL_COL_CACHE_SIZE - 1;

    @Shadow @Final private DensityFunction temperature;
    @Shadow @Final private DensityFunction humidity;
    @Shadow @Final private DensityFunction continentalness;
    @Shadow @Final private DensityFunction erosion;
    @Shadow @Final private DensityFunction depth;
    @Shadow @Final private DensityFunction weirdness;

    @Unique
    private record GlobalColSample(long key, float t, float h, float c, float e, float w, double dOff) {}

    @Unique private static final class MutableClimateCtx implements DensityFunction.FunctionContext {
        int x, y, z;
        int lastQx = Integer.MIN_VALUE;
        int lastQz = Integer.MIN_VALUE;
        Object lastSampler;
        float t, h, c, e, w;
        double dOff;
        boolean hasDoff;
        final int[] bx1 = new int[1];
        final int[] by1 = new int[1];
        final int[] bz1 = new int[1];
        final double[] out1 = new double[1];

        @Override public int blockX() { return x; }
        @Override public int blockY() { return y; }
        @Override public int blockZ() { return z; }
    }

    @Unique private static final ThreadLocal<MutableClimateCtx> CHUNKING$TL_CTX =
            ThreadLocal.withInitial(MutableClimateCtx::new);

    @Unique private volatile boolean chunking$checkedFlat2D;
    @Unique private boolean chunking$isFlat2D;
    @Unique private FlatCacheAccess chunking$fcTemp;
    @Unique private FlatCacheAccess chunking$fcHum;
    @Unique private FlatCacheAccess chunking$fcCont;
    @Unique private FlatCacheAccess chunking$fcEros;
    @Unique private FlatCacheAccess chunking$fcWeird;
    @Unique private FlatCacheAccess chunking$depthOffsetCache;
    @Unique private volatile boolean chunking$checkedColPlan;
    @Unique private boolean chunking$colPlanReady;
    @Unique private DensityFunction chunking$depthOffsetFn;
    @Unique private boolean chunking$depthIsConstY;
    @Unique private double chunking$dFromY;
    @Unique private double chunking$dToY;
    @Unique private double chunking$dFromVal;
    @Unique private double chunking$dToVal;

    @Unique private volatile boolean chunking$globalKernelsChecked;
    @Unique private NoiseJit.BoundKernel chunking$kTemp;
    @Unique private NoiseJit.BoundKernel chunking$kHum;
    @Unique private NoiseJit.BoundKernel chunking$kCont;
    @Unique private NoiseJit.BoundKernel chunking$kEros;
    @Unique private NoiseJit.BoundKernel chunking$kWeird;
    @Unique private NoiseJit.BoundKernel chunking$kDepthOff;
    @Unique private volatile AtomicReferenceArray<GlobalColSample> chunking$globalColCache;
    @Unique private volatile boolean chunking$checkedErosionYInvariant;
    @Unique private boolean chunking$erosionYInvariant;

    @Unique private static final MethodHandle CHUNKING$MERGED_FULL = chunking$resolveDfGetter(
            "dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction", "full", DensityFunction.class);
    @Unique private static final MethodHandle CHUNKING$CONST_VALUE = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$Constant", "value", double.class);
    @Unique private static final MethodHandle CHUNKING$MULADD_TYPE = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd", "specificType", Object.class);
    @Unique private static final MethodHandle CHUNKING$MULADD_INPUT = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd", "input", DensityFunction.class);
    @Unique private static final MethodHandle CHUNKING$MULADD_ARG = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$MulOrAdd", "argument", double.class);
    @Unique private static final MethodHandle CHUNKING$YGRAD_FROM_Y = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient", "fromY", int.class);
    @Unique private static final MethodHandle CHUNKING$YGRAD_TO_Y = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient", "toY", int.class);
    @Unique private static final MethodHandle CHUNKING$YGRAD_FROM_VAL = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient", "fromValue", double.class);
    @Unique private static final MethodHandle CHUNKING$YGRAD_TO_VAL = chunking$resolveDfGetter(
            "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient", "toValue", double.class);

    @Unique
    private static final ClassValue<MethodHandle> CHUNKING$YSCALE_GETTER = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                Method m = type.getDeclaredMethod("yScale");
                m.setAccessible(true);
                return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(double.class, Object.class));
            } catch (Throwable ignored) {
                return null;
            }
        }
    };

    @Unique
    private static MethodHandle chunking$resolveDfGetter(String className, String methodName, Class<?> retType) {
        try {
            Class<?> cls = Class.forName(className, false, DensityFunctions.class.getClassLoader());
            Method m = cls.getDeclaredMethod(methodName);
            m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(retType, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean chunking$isErosionYInvariant() {
        if (!this.chunking$checkedErosionYInvariant) {
            synchronized (this) {
                if (!this.chunking$checkedErosionYInvariant) {
                    this.chunking$erosionYInvariant = chunking$isYInvariant(this.erosion);
                    this.chunking$checkedErosionYInvariant = true;
                }
            }
        }
        return this.chunking$erosionYInvariant;
    }

    @Override
    public boolean chunking$isDepthConstY() {
        chunking$ensureColumnPlan();
        return this.chunking$colPlanReady && this.chunking$depthIsConstY;
    }

    @Unique
    private static boolean chunking$isYInvariant(DensityFunction root) {
        if (root == null) {
            return false;
        }
        final boolean[] ok = { true };
        root.mapAll(df -> {
            if (!ok[0]) {
                return df;
            }
            if (df instanceof FlatCacheAccess
                    || df instanceof DensityFunctions.TwoArgumentSimpleFunction
                    || df instanceof DensityFunctions.Spline) {
                return df;
            }
            if (ModCompat.isYInvariantModDensityFunction(df.getClass())
                    || "dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction"
                            .equals(df.getClass().getName())) {
                return df;
            }
            if (!com.chunking.engine.world.ModCompat.isVanillaClass(df.getClass())) {
                ok[0] = false;
                return df;
            }
            String cn = df.getClass().getSimpleName();
            if ("ShiftedNoise".equals(cn) || "Noise".equals(cn)) {
                try {
                    MethodHandle mh = CHUNKING$YSCALE_GETTER.get(df.getClass());
                    if (mh == null || (double) mh.invokeExact((Object) df) != 0.0D) {
                        ok[0] = false;
                    }
                } catch (Throwable t) {
                    ok[0] = false;
                }
                return df;
            }
            if ("Marker".equals(cn)) {
                if (df instanceof net.minecraft.world.level.levelgen.DensityFunctions.MarkerOrMarked m
                        && "BLEND_DENSITY".equals(m.type().name())) {
                    ok[0] = false;
                }
                return df;
            }
            if ("Constant".equals(cn) || "Zero".equals(cn)
                    || "ShiftA".equals(cn) || "ShiftB".equals(cn)
                    || "BlendOffset".equals(cn) || "BlendAlpha".equals(cn)
                    || "Cache2D".equals(cn) || "CacheAllInCell".equals(cn) || "CacheOnce".equals(cn)
                    || "HolderHolder".equals(cn) || "Mapped".equals(cn)
                    || "Clamp".equals(cn) || "RangeChoice".equals(cn)
                    || "EndIslandDensityFunction".equals(cn)) {
                return df;
            }
            ok[0] = false;
            return df;
        });
        return ok[0];
    }

    @Unique
    private static boolean chunking$isZeroConstant(DensityFunction d) {
        if (d == null) {
            return false;
        }
        String cn = d.getClass().getSimpleName();
        if ("Zero".equals(cn)) {
            return true;
        }
        if ("Constant".equals(cn) && CHUNKING$CONST_VALUE != null) {
            try {
                return (double) CHUNKING$CONST_VALUE.invokeExact((Object) d) == 0.0D;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Unique
    private static DensityFunction chunking$unwrapTransparent(DensityFunction df) {
        int guard = 0;
        while (df != null && guard++ < 16) {
            if (df instanceof DensityFunctions.HolderHolder hh) {
                df = hh.function().value();
                continue;
            }
            if ("dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction"
                    .equals(df.getClass().getName()) && CHUNKING$MERGED_FULL != null) {
                try {
                    DensityFunction full = (DensityFunction) CHUNKING$MERGED_FULL.invokeExact((Object) df);
                    if (full != null) {
                        df = full;
                        continue;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (ModCompat.isVanillaClass(df.getClass())) {
                String cn = df.getClass().getSimpleName();
                if (("CacheOnce".equals(cn) || "Cache2D".equals(cn))
                        && df instanceof DensityFunctions.MarkerOrMarked mom) {
                    df = mom.wrapped();
                    continue;
                }
                if ("Marker".equals(cn)
                        && df instanceof DensityFunctions.MarkerOrMarked mom
                        && !"BLEND_DENSITY".equals(mom.type().name())) {
                    df = mom.wrapped();
                    continue;
                }
                if ("MulOrAdd".equals(cn)
                        && CHUNKING$MULADD_TYPE != null && CHUNKING$MULADD_INPUT != null && CHUNKING$MULADD_ARG != null) {
                    try {
                        Object kind = CHUNKING$MULADD_TYPE.invokeExact((Object) df);
                        double arg = (double) CHUNKING$MULADD_ARG.invokeExact((Object) df);
                        String kindName = kind.toString();
                        if (("ADD".equals(kindName) && arg == 0.0D) || ("MUL".equals(kindName) && arg == 1.0D)) {
                            df = (DensityFunction) CHUNKING$MULADD_INPUT.invokeExact((Object) df);
                            continue;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (df instanceof DensityFunctions.TwoArgumentSimpleFunction two
                        && two.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
                    if (chunking$isZeroConstant(two.argument1())) {
                        df = two.argument2();
                        continue;
                    }
                    if (chunking$isZeroConstant(two.argument2())) {
                        df = two.argument1();
                        continue;
                    }
                }
            }
            break;
        }
        return df;
    }

    @Unique
    private void chunking$ensureColumnPlan() {
        if (this.chunking$checkedColPlan) {
            return;
        }
        synchronized (this) {
            if (this.chunking$checkedColPlan) {
                return;
            }
            DensityFunction uTemp = chunking$unwrapTransparent(this.temperature);
            DensityFunction uHum = chunking$unwrapTransparent(this.humidity);
            DensityFunction uCont = chunking$unwrapTransparent(this.continentalness);
            DensityFunction uEros = chunking$unwrapTransparent(this.erosion);
            DensityFunction uWeird = chunking$unwrapTransparent(this.weirdness);
            boolean flat5 = uTemp instanceof FlatCacheAccess
                    && uHum instanceof FlatCacheAccess
                    && uCont instanceof FlatCacheAccess
                    && uEros instanceof FlatCacheAccess
                    && uWeird instanceof FlatCacheAccess;
            if (flat5) {
                this.chunking$fcTemp = (FlatCacheAccess) uTemp;
                this.chunking$fcHum = (FlatCacheAccess) uHum;
                this.chunking$fcCont = (FlatCacheAccess) uCont;
                this.chunking$fcEros = (FlatCacheAccess) uEros;
                this.chunking$fcWeird = (FlatCacheAccess) uWeird;
                this.chunking$isFlat2D = true;
            } else {
                this.chunking$isFlat2D = false;
                if (!chunking$isYInvariant(this.temperature)
                        || !chunking$isYInvariant(this.humidity)
                        || !chunking$isYInvariant(this.continentalness)
                        || !chunking$isYInvariant(this.erosion)
                        || !chunking$isYInvariant(this.weirdness)) {
                    this.chunking$checkedFlat2D = true;
                    this.chunking$checkedColPlan = true;
                    return;
                }
            }

            DensityFunction unwrappedDepth = chunking$unwrapTransparent(this.depth);
            if (unwrappedDepth instanceof FlatCacheAccess fc) {
                this.chunking$depthIsConstY = true;
                this.chunking$depthOffsetCache = fc;
                this.chunking$depthOffsetFn = unwrappedDepth;
                this.chunking$colPlanReady = true;
                this.chunking$checkedFlat2D = true;
                this.chunking$checkedColPlan = true;
                return;
            }
            if (unwrappedDepth instanceof DensityFunctions.TwoArgumentSimpleFunction two
                    && two.type() == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
                DensityFunction a1 = chunking$unwrapTransparent(two.argument1());
                DensityFunction a2 = chunking$unwrapTransparent(two.argument2());
                DensityFunction yGrad = null;
                DensityFunction offFn = null;
                if (a2 != null && "YClampedGradient".equals(a2.getClass().getSimpleName())
                        && (a1 instanceof FlatCacheAccess || chunking$isYInvariant(a1))) {
                    offFn = a1;
                    yGrad = a2;
                } else if (a1 != null && "YClampedGradient".equals(a1.getClass().getSimpleName())
                        && (a2 instanceof FlatCacheAccess || chunking$isYInvariant(a2))) {
                    offFn = a2;
                    yGrad = a1;
                }
                if (offFn != null && yGrad != null
                        && CHUNKING$YGRAD_FROM_Y != null && CHUNKING$YGRAD_TO_Y != null
                        && CHUNKING$YGRAD_FROM_VAL != null && CHUNKING$YGRAD_TO_VAL != null) {
                    try {
                        this.chunking$dFromY = (double) (int) CHUNKING$YGRAD_FROM_Y.invokeExact((Object) yGrad);
                        this.chunking$dToY = (double) (int) CHUNKING$YGRAD_TO_Y.invokeExact((Object) yGrad);
                        this.chunking$dFromVal = (double) CHUNKING$YGRAD_FROM_VAL.invokeExact((Object) yGrad);
                        this.chunking$dToVal = (double) CHUNKING$YGRAD_TO_VAL.invokeExact((Object) yGrad);
                        this.chunking$depthOffsetFn = offFn;
                        if (offFn instanceof FlatCacheAccess fc) {
                            this.chunking$depthOffsetCache = fc;
                        }
                        this.chunking$colPlanReady = true;
                    } catch (Throwable ignored) {
                        this.chunking$colPlanReady = false;
                    }
                }
            } else if (!flat5 && chunking$isYInvariant(this.depth)) {
                this.chunking$depthIsConstY = true;
                this.chunking$depthOffsetFn = this.depth;
                this.chunking$colPlanReady = true;
            }
            this.chunking$checkedFlat2D = true;
            this.chunking$checkedColPlan = true;
        }
    }

    @Unique
    private void chunking$ensureGlobalKernels() {
        if (this.chunking$globalKernelsChecked) {
            return;
        }
        synchronized (this) {
            if (this.chunking$globalKernelsChecked) {
                return;
            }
            chunking$ensureColumnPlan();
            if (this.chunking$colPlanReady && !this.chunking$isFlat2D) {
                this.chunking$kTemp = NoiseJit.compileAndBind(this.temperature);
                this.chunking$kHum = NoiseJit.compileAndBind(this.humidity);
                this.chunking$kCont = NoiseJit.compileAndBind(this.continentalness);
                this.chunking$kEros = NoiseJit.compileAndBind(this.erosion);
                this.chunking$kWeird = NoiseJit.compileAndBind(this.weirdness);
                if (this.chunking$depthOffsetFn != null) {
                    this.chunking$kDepthOff = NoiseJit.compileAndBind(this.chunking$depthOffsetFn);
                }
                this.chunking$globalColCache = new AtomicReferenceArray<>(GLOBAL_COL_CACHE_SIZE);
            }
            this.chunking$globalKernelsChecked = true;
        }
    }

    @Unique
    private static double chunking$evalPoint(
            NoiseJit.BoundKernel k,
            DensityFunction fallback,
            MutableClimateCtx ctx
    ) {
        if (k != null) {
            ctx.bx1[0] = ctx.x;
            ctx.by1[0] = ctx.y;
            ctx.bz1[0] = ctx.z;
            k.evalBatch(ctx.bx1, ctx.by1, ctx.bz1, ctx.out1, 1, null, false);
            return ctx.out1[0];
        }
        return fallback.compute(ctx);
    }

    @Unique
    private static final class BiomeColBuf {
        Object[] grid = new Object[0];
        double[] yGrad = new double[0];
        long[] dQs = new long[0];
        int[] defYi = new int[0];
        long[] defDQs = new long[0];
        Object[] defOut = new Object[0];

        void ensure(int secCount) {
            int needGrid = secCount << 6;
            if (this.grid.length < needGrid) {
                this.grid = new Object[needGrid];
            }
            int needY = secCount << 2;
            if (this.yGrad.length < needY) {
                this.yGrad = new double[needY];
                this.dQs = new long[needY];
                this.defYi = new int[needY];
                this.defDQs = new long[needY];
                this.defOut = new Object[needY];
            }
        }
    }

    @Unique
    private static final ThreadLocal<BiomeColBuf> CHUNKING$COL_BUF = ThreadLocal.withInitial(BiomeColBuf::new);

    @Override
    @SuppressWarnings("unchecked")
    public boolean chunking$fillBiomesColumnFast(ChunkAccess chunkAccess, ClimateRTreeAccess<Holder<Biome>> biomeTree) {
        chunking$ensureColumnPlan();
        if (!this.chunking$colPlanReady) {
            return false;
        }
        LevelChunkSection[] sections = chunkAccess.getSections();
        int secCount = sections.length;
        if (secCount == 0) {
            return true;
        }

        BiomeColBuf buf = CHUNKING$COL_BUF.get();
        buf.ensure(secCount);
        Object[] grid = buf.grid;
        double[] yGrad = buf.yGrad;
        long[] dQs = buf.dQs;
        int totalYQuarts = secCount << 2;
        boolean constYDepth = this.chunking$depthIsConstY;

        if (!constYDepth) {
            int minQuartY = QuartPos.fromBlock(chunkAccess.getMinY());
            double fromY = this.chunking$dFromY;
            double toY = this.chunking$dToY;
            double fromVal = this.chunking$dFromVal;
            double toVal = this.chunking$dToVal;
            for (int yi = 0; yi < totalYQuarts; yi++) {
                int by = QuartPos.toBlock(minQuartY + yi);
                yGrad[yi] = Mth.clampedMap((double) by, fromY, toY, fromVal, toVal);
            }
        }

        int baseQuartX = QuartPos.fromBlock(chunkAccess.getPos().getMinBlockX());
        int baseQuartZ = QuartPos.fromBlock(chunkAccess.getPos().getMinBlockZ());
        boolean flat2D = this.chunking$isFlat2D;
        FlatCacheAccess fcT = this.chunking$fcTemp;
        FlatCacheAccess fcH = this.chunking$fcHum;
        FlatCacheAccess fcC = this.chunking$fcCont;
        FlatCacheAccess fcE = this.chunking$fcEros;
        FlatCacheAccess fcW = this.chunking$fcWeird;
        FlatCacheAccess depthCache = this.chunking$depthOffsetCache;
        DensityFunction depthFn = this.chunking$depthOffsetFn;
        MutableClimateCtx ctx = (!flat2D || (depthCache == null && depthFn != null)) ? CHUNKING$TL_CTX.get() : null;
        if (ctx != null) {
            ctx.y = 0;
        }
        boolean useColBatch = biomeTree.chunking$supportsColumnBatch();

        for (int qx = 0; qx < 4; qx++) {
            int bx = QuartPos.toBlock(baseQuartX + qx);
            for (int qz = 0; qz < 4; qz++) {
                int bz = QuartPos.toBlock(baseQuartZ + qz);
                int colXZ = (qz << 2) | qx;
                long tQ, hQ, cQ, eQ, wQ;
                if (flat2D) {
                    tQ = Climate.quantizeCoord((float) fcT.chunking$sampleQuartFast(bx, bz));
                    hQ = Climate.quantizeCoord((float) fcH.chunking$sampleQuartFast(bx, bz));
                    cQ = Climate.quantizeCoord((float) fcC.chunking$sampleQuartFast(bx, bz));
                    eQ = Climate.quantizeCoord((float) fcE.chunking$sampleQuartFast(bx, bz));
                    wQ = Climate.quantizeCoord((float) fcW.chunking$sampleQuartFast(bx, bz));
                } else {
                    ctx.x = bx;
                    ctx.z = bz;
                    tQ = Climate.quantizeCoord((float) this.temperature.compute(ctx));
                    hQ = Climate.quantizeCoord((float) this.humidity.compute(ctx));
                    cQ = Climate.quantizeCoord((float) this.continentalness.compute(ctx));
                    eQ = Climate.quantizeCoord((float) this.erosion.compute(ctx));
                    wQ = Climate.quantizeCoord((float) this.weirdness.compute(ctx));
                }
                double dOff;
                if (depthCache != null) {
                    dOff = depthCache.chunking$sampleQuartFast(bx, bz);
                } else if (depthFn != null) {
                    ctx.x = bx;
                    ctx.z = bz;
                    dOff = depthFn.compute(ctx);
                } else {
                    dOff = 0.0D;
                }

                if (constYDepth) {
                    Holder<Biome> colBiome = biomeTree.chunking$searchRaw(
                            tQ, hQ, cQ, eQ, Climate.quantizeCoord((float) dOff), wQ);
                    for (int yi = 0; yi < totalYQuarts; yi++) {
                        grid[(yi << 4) | colXZ] = colBiome;
                    }
                } else {
                    for (int yi = 0; yi < totalYQuarts; yi++) {
                        dQs[yi] = Climate.quantizeCoord((float) (yGrad[yi] + dOff));
                    }
                    if (useColBatch) {
                        biomeTree.chunking$searchColumnBatch(tQ, hQ, cQ, eQ, wQ, dQs, totalYQuarts, grid, colXZ, 16);
                    } else {
                        for (int yi = 0; yi < totalYQuarts; yi++) {
                            grid[(yi << 4) | colXZ] = biomeTree.chunking$searchRaw(tQ, hQ, cQ, eQ, dQs[yi], wQ);
                        }
                    }
                }
            }
        }

        chunking$writeBiomeGridToSections(sections, grid);
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean chunking$fillTerraBlenderBiomesColumnFast(ChunkAccess chunkAccess, Climate.ParameterList<?> tbParams) {
        chunking$ensureColumnPlan();
        if (!this.chunking$colPlanReady || tbParams == null) {
            return false;
        }
        LevelChunkSection[] sections = chunkAccess.getSections();
        int secCount = sections.length;
        if (secCount == 0) {
            return true;
        }
        Climate.ParameterList<?> tlParams = ModCompat.threadLocalTerraBlenderParams(tbParams);

        BiomeColBuf buf = CHUNKING$COL_BUF.get();
        buf.ensure(secCount);
        Object[] grid = buf.grid;
        double[] yGrad = buf.yGrad;
        long[] dQs = buf.dQs;
        int totalYQuarts = secCount << 2;
        int minQuartY = QuartPos.fromBlock(chunkAccess.getMinY());
        boolean constYDepth = this.chunking$depthIsConstY;

        if (!constYDepth) {
            double fromY = this.chunking$dFromY;
            double toY = this.chunking$dToY;
            double fromVal = this.chunking$dFromVal;
            double toVal = this.chunking$dToVal;
            for (int yi = 0; yi < totalYQuarts; yi++) {
                int by = QuartPos.toBlock(minQuartY + yi);
                yGrad[yi] = Mth.clampedMap((double) by, fromY, toY, fromVal, toVal);
            }
        }

        int baseQuartX = QuartPos.fromBlock(chunkAccess.getPos().getMinBlockX());
        int baseQuartZ = QuartPos.fromBlock(chunkAccess.getPos().getMinBlockZ());
        boolean flat2D = this.chunking$isFlat2D;
        FlatCacheAccess fcT = this.chunking$fcTemp;
        FlatCacheAccess fcH = this.chunking$fcHum;
        FlatCacheAccess fcC = this.chunking$fcCont;
        FlatCacheAccess fcE = this.chunking$fcEros;
        FlatCacheAccess fcW = this.chunking$fcWeird;
        FlatCacheAccess depthCache = this.chunking$depthOffsetCache;
        DensityFunction depthFn = this.chunking$depthOffsetFn;
        MutableClimateCtx ctx = (!flat2D || (depthCache == null && depthFn != null)) ? CHUNKING$TL_CTX.get() : null;
        if (ctx != null) {
            ctx.y = 0;
        }

        for (int qx = 0; qx < 4; qx++) {
            int absQx = baseQuartX + qx;
            int bx = QuartPos.toBlock(absQx);
            for (int qz = 0; qz < 4; qz++) {
                int absQz = baseQuartZ + qz;
                int bz = QuartPos.toBlock(absQz);
                int colXZ = (qz << 2) | qx;
                long tQ, hQ, cQ, eQ, wQ;
                if (flat2D) {
                    tQ = Climate.quantizeCoord((float) fcT.chunking$sampleQuartFast(bx, bz));
                    hQ = Climate.quantizeCoord((float) fcH.chunking$sampleQuartFast(bx, bz));
                    cQ = Climate.quantizeCoord((float) fcC.chunking$sampleQuartFast(bx, bz));
                    eQ = Climate.quantizeCoord((float) fcE.chunking$sampleQuartFast(bx, bz));
                    wQ = Climate.quantizeCoord((float) fcW.chunking$sampleQuartFast(bx, bz));
                } else {
                    ctx.x = bx;
                    ctx.z = bz;
                    tQ = Climate.quantizeCoord((float) this.temperature.compute(ctx));
                    hQ = Climate.quantizeCoord((float) this.humidity.compute(ctx));
                    cQ = Climate.quantizeCoord((float) this.continentalness.compute(ctx));
                    eQ = Climate.quantizeCoord((float) this.erosion.compute(ctx));
                    wQ = Climate.quantizeCoord((float) this.weirdness.compute(ctx));
                }
                double dOff;
                if (depthCache != null) {
                    dOff = depthCache.chunking$sampleQuartFast(bx, bz);
                } else if (depthFn != null) {
                    ctx.x = bx;
                    ctx.z = bz;
                    dOff = depthFn.compute(ctx);
                } else {
                    dOff = 0.0D;
                }

                int uniqueness = ModCompat.getTerraBlenderUniqueness(tlParams, absQx, 0, absQz);
                ClimateRTreeAccess<Holder<Biome>> regionAcc =
                        ModCompat.getTerraBlenderTree(tlParams, uniqueness);
                ClimateRTreeAccess<Holder<Biome>> vanillaAcc = (uniqueness == 0)
                        ? regionAcc
                        : ModCompat.getTerraBlenderTree(tlParams, 0);
                if (regionAcc == null || vanillaAcc == null) {
                    return false;
                }

                if (uniqueness == 0) {
                    if (constYDepth) {
                        Holder<Biome> colBiome = vanillaAcc.chunking$searchRaw(
                                tQ, hQ, cQ, eQ, Climate.quantizeCoord((float) dOff), wQ);
                        for (int yi = 0; yi < totalYQuarts; yi++) {
                            grid[(yi << 4) | colXZ] = colBiome;
                        }
                    } else {
                        for (int yi = 0; yi < totalYQuarts; yi++) {
                            dQs[yi] = Climate.quantizeCoord((float) (yGrad[yi] + dOff));
                        }
                        if (vanillaAcc.chunking$supportsColumnBatch()) {
                            vanillaAcc.chunking$searchColumnBatch(tQ, hQ, cQ, eQ, wQ, dQs, totalYQuarts, grid, colXZ, 16);
                        } else {
                            for (int yi = 0; yi < totalYQuarts; yi++) {
                                grid[(yi << 4) | colXZ] = vanillaAcc.chunking$searchRaw(tQ, hQ, cQ, eQ, dQs[yi], wQ);
                            }
                        }
                    }
                } else if (constYDepth) {
                    long dQ = Climate.quantizeCoord((float) dOff);
                    Holder<Biome> colBiome = regionAcc.chunking$searchRaw(tQ, hQ, cQ, eQ, dQ, wQ);
                    if (ModCompat.isTerraBlenderDeferred(colBiome)) {
                        colBiome = vanillaAcc.chunking$searchRaw(tQ, hQ, cQ, eQ, dQ, wQ);
                    }
                    for (int yi = 0; yi < totalYQuarts; yi++) {
                        grid[(yi << 4) | colXZ] = colBiome;
                    }
                } else {
                    for (int yi = 0; yi < totalYQuarts; yi++) {
                        dQs[yi] = Climate.quantizeCoord((float) (yGrad[yi] + dOff));
                    }
                    if (regionAcc.chunking$supportsColumnBatch()) {
                        regionAcc.chunking$searchColumnBatch(tQ, hQ, cQ, eQ, wQ, dQs, totalYQuarts, grid, colXZ, 16);
                        int defCount = 0;
                        int[] defYi = buf.defYi;
                        long[] defDQs = buf.defDQs;
                        for (int yi = 0; yi < totalYQuarts; yi++) {
                            Holder<Biome> found = (Holder<Biome>) grid[(yi << 4) | colXZ];
                            if (ModCompat.isTerraBlenderDeferred(found)) {
                                defYi[defCount] = yi;
                                defDQs[defCount] = dQs[yi];
                                defCount++;
                            }
                        }
                        if (defCount > 0) {
                            if (vanillaAcc.chunking$supportsColumnBatch()) {
                                Object[] defOut = buf.defOut;
                                vanillaAcc.chunking$searchColumnBatch(tQ, hQ, cQ, eQ, wQ, defDQs, defCount, defOut, 0, 1);
                                for (int k = 0; k < defCount; k++) {
                                    grid[(defYi[k] << 4) | colXZ] = defOut[k];
                                }
                            } else {
                                for (int k = 0; k < defCount; k++) {
                                    grid[(defYi[k] << 4) | colXZ] =
                                            vanillaAcc.chunking$searchRaw(tQ, hQ, cQ, eQ, defDQs[k], wQ);
                                }
                            }
                        }
                    } else {
                        for (int yi = 0; yi < totalYQuarts; yi++) {
                            long dQ = dQs[yi];
                            Holder<Biome> found = regionAcc.chunking$searchRaw(tQ, hQ, cQ, eQ, dQ, wQ);
                            if (ModCompat.isTerraBlenderDeferred(found)) {
                                found = vanillaAcc.chunking$searchRaw(tQ, hQ, cQ, eQ, dQ, wQ);
                            }
                            grid[(yi << 4) | colXZ] = found;
                        }
                    }
                }
            }
        }

        chunking$writeBiomeGridToSections(sections, grid);
        return true;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static void chunking$writeBiomeGridToSections(LevelChunkSection[] sections, Object[] grid) {
        int secCount = sections.length;
        for (int sy = 0; sy < secCount; sy++) {
            PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) (Object) sections[sy].getBiomes();
            int secBase = sy << 6;
            Holder<Biome> first = (Holder<Biome>) grid[secBase];
            boolean uniform = true;
            for (int i = 1; i < 64; i++) {
                if (grid[secBase + i] != first) {
                    uniform = false;
                    break;
                }
            }
            if (uniform && ((PalettedContainerAccess<Holder<Biome>>) container).chunking$trySetUniformSingleValue(first)) {
                continue;
            }
            for (int qy = 0; qy < 4; qy++) {
                int yBase = secBase | (qy << 4);
                for (int qz = 0; qz < 4; qz++) {
                    int zBase = yBase | (qz << 2);
                    container.getAndSetUnchecked(0, qy, qz, (Holder<Biome>) grid[zBase]);
                    container.getAndSetUnchecked(1, qy, qz, (Holder<Biome>) grid[zBase | 1]);
                    container.getAndSetUnchecked(2, qy, qz, (Holder<Biome>) grid[zBase | 2]);
                    container.getAndSetUnchecked(3, qy, qz, (Holder<Biome>) grid[zBase | 3]);
                }
            }
        }
    }

    @Unique
    private void chunking$evalClimateIntoCtx(int qx, int qy, int qz, MutableClimateCtx ctx) {
        if (!this.chunking$checkedFlat2D) {
            chunking$ensureColumnPlan();
        }

        int bx = QuartPos.toBlock(qx);
        int by = QuartPos.toBlock(qy);
        int bz = QuartPos.toBlock(qz);
        ctx.x = bx;
        ctx.y = by;
        ctx.z = bz;

        if (this.chunking$isFlat2D) {
            if (ctx.lastQx != qx || ctx.lastQz != qz || ctx.lastSampler != this) {
                ctx.t = (float) this.chunking$fcTemp.chunking$sampleQuartFast(bx, bz);
                ctx.h = (float) this.chunking$fcHum.chunking$sampleQuartFast(bx, bz);
                ctx.c = (float) this.chunking$fcCont.chunking$sampleQuartFast(bx, bz);
                ctx.e = (float) this.chunking$fcEros.chunking$sampleQuartFast(bx, bz);
                ctx.w = (float) this.chunking$fcWeird.chunking$sampleQuartFast(bx, bz);
                if (this.chunking$depthOffsetCache != null) {
                    ctx.dOff = this.chunking$depthOffsetCache.chunking$sampleQuartFast(bx, bz);
                    ctx.hasDoff = true;
                } else if (this.chunking$depthOffsetFn != null) {
                    ctx.y = 0;
                    ctx.dOff = this.chunking$depthOffsetFn.compute(ctx);
                    ctx.y = by;
                    ctx.hasDoff = true;
                } else {
                    ctx.dOff = 0.0D;
                    ctx.hasDoff = false;
                }
                ctx.lastQx = qx;
                ctx.lastQz = qz;
                ctx.lastSampler = this;
            }
            return;
        }

        chunking$ensureGlobalKernels();
        if (this.chunking$colPlanReady && this.chunking$globalColCache != null) {
            if (ctx.lastQx == qx && ctx.lastQz == qz && ctx.lastSampler == this) {
                return;
            }
            long key = ChunkPos.pack(qx, qz);
            int slot = ((int) HashCommon.mix(key)) & GLOBAL_COL_CACHE_MASK;
            AtomicReferenceArray<GlobalColSample> cache = this.chunking$globalColCache;
            GlobalColSample hit = cache.getOpaque(slot);
            if (hit != null && hit.key() == key) {
                ctx.t = hit.t();
                ctx.h = hit.h();
                ctx.c = hit.c();
                ctx.e = hit.e();
                ctx.w = hit.w();
                ctx.dOff = hit.dOff();
                ctx.hasDoff = true;
                ctx.lastQx = qx;
                ctx.lastQz = qz;
                ctx.lastSampler = this;
                return;
            }
            ctx.y = 0;
            float t = (float) chunking$evalPoint(this.chunking$kTemp, this.temperature, ctx);
            float h = (float) chunking$evalPoint(this.chunking$kHum, this.humidity, ctx);
            float c = (float) chunking$evalPoint(this.chunking$kCont, this.continentalness, ctx);
            float e = (float) chunking$evalPoint(this.chunking$kEros, this.erosion, ctx);
            float w = (float) chunking$evalPoint(this.chunking$kWeird, this.weirdness, ctx);
            double dOff = chunking$evalPoint(this.chunking$kDepthOff, this.chunking$depthOffsetFn, ctx);
            ctx.y = by;
            cache.setOpaque(slot, new GlobalColSample(key, t, h, c, e, w, dOff));
            ctx.t = t;
            ctx.h = h;
            ctx.c = c;
            ctx.e = e;
            ctx.w = w;
            ctx.dOff = dOff;
            ctx.hasDoff = true;
            ctx.lastQx = qx;
            ctx.lastQz = qz;
            ctx.lastSampler = this;
            return;
        }

        ctx.t = (float) this.temperature.compute(ctx);
        ctx.h = (float) this.humidity.compute(ctx);
        ctx.c = (float) this.continentalness.compute(ctx);
        ctx.e = (float) this.erosion.compute(ctx);
        ctx.w = (float) this.weirdness.compute(ctx);
        ctx.hasDoff = false;
    }

    @Override
    public <T> T chunking$sampleAndSearchRaw(int qx, int qy, int qz, ClimateRTreeAccess<T> rtree) {
        MutableClimateCtx ctx = CHUNKING$TL_CTX.get();
        chunking$evalClimateIntoCtx(qx, qy, qz, ctx);
        int by = QuartPos.toBlock(qy);
        ctx.y = by;
        float d = ctx.hasDoff
                ? (this.chunking$depthIsConstY
                        ? (float) ctx.dOff
                        : (float) (Mth.clampedMap((double) by, this.chunking$dFromY, this.chunking$dToY, this.chunking$dFromVal, this.chunking$dToVal) + ctx.dOff))
                : (float) this.depth.compute(ctx);
        return rtree.chunking$searchRaw(
                Climate.quantizeCoord(ctx.t),
                Climate.quantizeCoord(ctx.h),
                Climate.quantizeCoord(ctx.c),
                Climate.quantizeCoord(ctx.e),
                Climate.quantizeCoord(d),
                Climate.quantizeCoord(ctx.w)
        );
    }

    @Override
    public Holder<Biome> chunking$sampleAndSearchTerraBlenderRaw(int qx, int qy, int qz, Climate.ParameterList<?> tlParams) {
        MutableClimateCtx ctx = CHUNKING$TL_CTX.get();
        chunking$evalClimateIntoCtx(qx, qy, qz, ctx);
        int by = QuartPos.toBlock(qy);
        ctx.y = by;
        float d = ctx.hasDoff
                ? (this.chunking$depthIsConstY
                        ? (float) ctx.dOff
                        : (float) (Mth.clampedMap((double) by, this.chunking$dFromY, this.chunking$dToY, this.chunking$dFromVal, this.chunking$dToVal) + ctx.dOff))
                : (float) this.depth.compute(ctx);
        return ModCompat.searchTerraBlenderRaw(
                tlParams,
                qx,
                qy,
                qz,
                Climate.quantizeCoord(ctx.t),
                Climate.quantizeCoord(ctx.h),
                Climate.quantizeCoord(ctx.c),
                Climate.quantizeCoord(ctx.e),
                Climate.quantizeCoord(d),
                Climate.quantizeCoord(ctx.w)
        );
    }

    /**
     * @author Chunking
     * @reason Eliminate SinglePointContext and CallbackInfoReturnable allocations and cache 2D channels across Y.
     */
    @Overwrite
    public Climate.TargetPoint sample(int qx, int qy, int qz) {
        if (!ModConfig.SIMD_NOISE) {
            int i = QuartPos.toBlock(qx);
            int j = QuartPos.toBlock(qy);
            int k = QuartPos.toBlock(qz);
            DensityFunction.FunctionContext ctx = new DensityFunction.SinglePointContext(i, j, k);
            return Climate.target(
                    (float) this.temperature.compute(ctx),
                    (float) this.humidity.compute(ctx),
                    (float) this.continentalness.compute(ctx),
                    (float) this.erosion.compute(ctx),
                    (float) this.depth.compute(ctx),
                    (float) this.weirdness.compute(ctx)
            );
        }
        MutableClimateCtx ctx = CHUNKING$TL_CTX.get();
        chunking$evalClimateIntoCtx(qx, qy, qz, ctx);
        int by = QuartPos.toBlock(qy);
        ctx.y = by;
        float d = ctx.hasDoff
                ? (this.chunking$depthIsConstY
                        ? (float) ctx.dOff
                        : (float) (Mth.clampedMap((double) by, this.chunking$dFromY, this.chunking$dToY, this.chunking$dFromVal, this.chunking$dToVal) + ctx.dOff))
                : (float) this.depth.compute(ctx);
        return Climate.target(ctx.t, ctx.h, ctx.c, ctx.e, d, ctx.w);
    }
}
