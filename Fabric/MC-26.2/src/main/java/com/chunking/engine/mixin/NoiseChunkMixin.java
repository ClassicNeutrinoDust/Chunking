package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.NoiseChunkAccessor;
import com.chunking.engine.access.AquiferAccess;
import com.chunking.engine.access.CacheAllInCellAccess;
import com.chunking.engine.simd.CellSkipper;
import com.chunking.engine.simd.NoiseJit;
import com.chunking.engine.access.NoiseSliceEval;
import com.chunking.engine.access.NoiseInterpolatorAccess;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces per-voxel virtual {@code DensityFunction.compute()} dispatch inside
 * {@link NoiseChunk#fillAllDirectly} and vertical slice fills with SteelMC-style compiled
 * Java 25 native-JIT DAG kernels, and fast-skips inactive ore-vein / pure-sky-air cells.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements NoiseSliceEval {
    @Shadow
    @Final
    private int cellCountXZ;

    @Shadow
    @Final
    private int cellCountY;

    @Shadow
    @Final
    private int cellNoiseMinY;

    @Shadow
    @Final
    private int firstCellZ;

    @Shadow
    @Final
    private int cellWidth;

    @Shadow
    @Final
    private int cellHeight;

    @Shadow
    @Final
    private List<NoiseChunk.NoiseInterpolator> interpolators;

    @Shadow
    @Final
    private List<?> cellCaches;

    @Mutable
    @Shadow
    @Final
    private Map<DensityFunction, DensityFunction> wrapped;

    @Shadow
    @Final
    private Aquifer aquifer;

    @Shadow
    @Final
    private DensityFunction fullNoiseDensity;

    @Shadow
    @Final
    private DensityFunction preliminarySurfaceLevel;

    @Shadow
    @Final
    private DensityFunction.ContextProvider sliceFillingContextProvider;

    @Shadow
    @Final
    private Blender blender;

    @Shadow
    @Final
    private net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker beardifier;

    @Shadow
    protected abstract int computePreliminarySurfaceLevel(long packedCol);

    @Shadow
    private DensityFunction wrapNew(DensityFunction df) {
        throw new AssertionError();
    }

    @Unique
    private java.util.concurrent.atomic.AtomicLongArray chunking$sharedPrelimCache;

    @Unique
    private java.util.concurrent.atomic.AtomicReferenceArray<com.chunking.engine.access.RandomStateAccess.BorderSliceEntry> chunking$sharedBorderSliceCache;

    @Unique
    private net.minecraft.world.level.levelgen.NoiseRouter chunking$initRouter;

    @Unique
    private NoiseJit.NoiseChunkPlan chunking$plan;

    @Unique
    private java.util.List<Object> chunking$flatCaches;

    @Unique
    private java.util.List<Object> chunking$cache2Ds;

    @Unique
    private CacheAllInCellAccess chunking$fullNoiseCell;

    @Unique
    private CacheAllInCellAccess chunking$veinToggleCell;

    @Unique
    private CacheAllInCellAccess chunking$veinRidgedCell;

    @Unique
    private CacheAllInCellAccess chunking$veinGapCell;

    @Unique
    private net.minecraft.world.level.levelgen.PositionalRandomFactory chunking$oreRandom;

    @Unique
    private net.minecraft.world.level.levelgen.NoiseRouter chunking$wrappedRouter;

    @Unique
    private net.minecraft.world.level.biome.Climate.Sampler chunking$cachedSampler;

    @Unique
    private NoiseInterpolatorAccess chunking$veinToggleInterp;

    @Unique
    private double[] chunking$activeVtValues;

    @Unique
    private boolean chunking$lastCellAllPositive;

    @Unique
    private boolean chunking$lastCellOreInactive;

    @Unique
    private CellSkipper chunking$cellEstimator;

    @Unique
    private boolean chunking$checkedEstimator;

    @Unique
    private boolean chunking$vanillaFillSlice;

    @Unique
    private static double chunking$copperMinToggleForRange(int startY, int endY) {
        int lo = Math.max(0, startY);
        int hi = Math.min(50, endY);
        if (lo > hi) {
            return Double.POSITIVE_INFINITY;
        }
        int maxDist = (lo <= 25 && hi >= 25)
                ? 25
                : Math.max(Math.min(50 - lo, lo), Math.min(50 - hi, hi));
        double maxRoundoff = net.minecraft.util.Mth.clampedMap((double) maxDist, 0.0D, 20.0D, -0.2D, 0.0D);
        return (double) 0.4F - maxRoundoff;
    }

    @Unique
    private static double chunking$ironMinAbsToggleForRange(int startY, int endY) {
        int lo = Math.max(-60, startY);
        int hi = Math.min(-8, endY);
        if (lo > hi) {
            return Double.POSITIVE_INFINITY;
        }
        int maxDist = (lo <= -34 && hi >= -34)
                ? 26
                : Math.max(Math.min(-8 - lo, lo + 60), Math.min(-8 - hi, hi + 60));
        double maxRoundoff = net.minecraft.util.Mth.clampedMap((double) maxDist, 0.0D, 20.0D, -0.2D, 0.0D);
        return (double) 0.4F - maxRoundoff;
    }

    @Unique
    private static boolean chunking$isVoxelOreToggleCandidate(double vt, int by) {
        final int minY;
        final int maxY;
        if (vt > 0.0D) {
            minY = 0;
            maxY = 50;
        } else {
            minY = -60;
            maxY = -8;
        }
        int dMax = maxY - by;
        int dMin = by - minY;
        if (dMin < 0 || dMax < 0) {
            return false;
        }
        int minDist = Math.min(dMax, dMin);
        double edgeRoundoff = net.minecraft.util.Mth.clampedMap((double) minDist, 0.0D, 20.0D, -0.2D, 0.0D);
        return Math.abs(vt) + edgeRoundoff >= (double) 0.4F;
    }

    @Unique
    private boolean chunking$computeOreInactive(int startY, int endY, int cellY, int cellZ) {
        if (SharedConstants.DEBUG_ORE_VEINS) {
            return false;
        }
        if (this.chunking$oreRandom == null && this.chunking$vtFn == null) {
            return true;
        }
        if (com.chunking.engine.world.FastOreVeins.isRangeOutsideOreVeinY(startY, endY)) {
            return true;
        }
        NoiseInterpolatorAccess vtInterp = this.chunking$veinToggleInterp;
        if (!com.chunking.engine.world.FastOreVeins.IS_VANILLA_VEINS) {
            if (vtInterp != null) {
                vtInterp.chunking$selectCellYZ(cellY, cellZ);
            }
            return false;
        }
        if (vtInterp != null) {
            vtInterp.chunking$selectCellYZ(cellY, cellZ);
            double v0 = vtInterp.chunking$getNoise000();
            double v1 = vtInterp.chunking$getNoise100();
            double v2 = vtInterp.chunking$getNoise010();
            double v3 = vtInterp.chunking$getNoise110();
            double v4 = vtInterp.chunking$getNoise001();
            double v5 = vtInterp.chunking$getNoise101();
            double v6 = vtInterp.chunking$getNoise011();
            double v7 = vtInterp.chunking$getNoise111();
            double copperThr = chunking$copperMinToggleForRange(startY, endY);
            if (copperThr <= 1.0D && (v0 >= copperThr || v1 >= copperThr || v2 >= copperThr || v3 >= copperThr
                    || v4 >= copperThr || v5 >= copperThr || v6 >= copperThr || v7 >= copperThr)) {
                return false;
            }
            double ironThr = chunking$ironMinAbsToggleForRange(startY, endY);
            if (ironThr <= 1.0D && (v0 <= -ironThr || v1 <= -ironThr || v2 <= -ironThr || v3 <= -ironThr
                    || v4 <= -ironThr || v5 <= -ironThr || v6 <= -ironThr || v7 <= -ironThr)) {
                return false;
            }
            return true;
        }
        return false;
    }

    @Shadow
    private boolean fillingCell;

    @Shadow
    private int cellStartBlockX;

    @Shadow
    private int cellStartBlockY;

    @Shadow
    private int cellStartBlockZ;

    @Shadow
    private int inCellX;

    @Shadow
    private int inCellY;

    @Shadow
    private int inCellZ;

    @Shadow
    private int arrayIndex;

    @Shadow
    private long interpolationCounter;

    @Shadow
    private long arrayInterpolationCounter;

    @Shadow
    protected abstract BlockState getInterpolatedState();

    @Unique
    private boolean chunking$lastCellAllNonPositive;

    @Unique
    private int chunking$maxSliceCount;

    @Unique
    private static final class SliceCoordScratch {
        int[] bx = new int[64];
        int[] by = new int[64];
        int[] bz = new int[64];

        void ensure(int count) {
            if (this.bx.length < count) {
                this.bx = new int[count];
                this.by = new int[count];
                this.bz = new int[count];
            }
        }
    }

    @Unique
    private static final ThreadLocal<SliceCoordScratch> CHUNKING$SLICE_COORDS_TL =
            ThreadLocal.withInitial(SliceCoordScratch::new);

    @Unique
    private DensityFunction chunking$jitFn0;

    @Unique
    private NoiseJit.BoundKernel chunking$jitK0;

    @Unique
    private DensityFunction chunking$jitFn1;

    @Unique
    private NoiseJit.BoundKernel chunking$jitK1;

    @Unique
    private java.util.IdentityHashMap<DensityFunction, NoiseJit.BoundKernel> chunking$localJitCache;

    @Unique
    private DensityFunction chunking$lastSkippedFn;

    @Unique
    private NoiseJit.BoundKernel chunking$resolveJitKernel(DensityFunction function) {
        if (function == null || function == this.chunking$lastSkippedFn) {
            return null;
        }
        if (function == this.chunking$jitFn0) {
            return this.chunking$jitK0;
        }
        if (function == this.chunking$jitFn1) {
            return this.chunking$jitK1;
        }
        if (this.chunking$localJitCache != null) {
            NoiseJit.BoundKernel cached = this.chunking$localJitCache.get(function);
            if (cached != null) {
                return cached;
            }
        }
        NoiseJit.BoundKernel bound = null;
        boolean matchedPlanSlot = false;
        if (this.chunking$plan != null) {
            List<?> caches = this.cellCaches;
            for (int i = 0, n = caches.size(); i < n; i++) {
                if (caches.get(i) instanceof CacheAllInCellAccess cell
                        && cell.chunking$cellNoiseFiller() == function) {
                    matchedPlanSlot = true;
                    List<Object> flats = this.chunking$flatCaches != null
                            ? this.chunking$flatCaches : java.util.Collections.emptyList();
                    List<Object> c2ds = this.chunking$cache2Ds != null
                            ? this.chunking$cache2Ds : java.util.Collections.emptyList();
                    bound = this.chunking$plan.resolve(
                            NoiseJit.PLAN_CELL_FILLER, i, function,
                            flats, c2ds, this.interpolators, this.beardifier);
                    break;
                }
            }
        }
        if (bound == null && !matchedPlanSlot) {
            bound = NoiseJit.compileAndBind(function);
        }
        if (bound == null) {
            this.chunking$lastSkippedFn = function;
            return null;
        }
        if (this.chunking$jitFn0 == null) {
            this.chunking$jitFn0 = function;
            this.chunking$jitK0 = bound;
        } else if (this.chunking$jitFn1 == null) {
            this.chunking$jitFn1 = function;
            this.chunking$jitK1 = bound;
        } else {
            if (this.chunking$localJitCache == null) {
                this.chunking$localJitCache = new java.util.IdentityHashMap<>(6);
            }
            this.chunking$localJitCache.put(function, bound);
        }
        return bound;
    }

    @Override
    public NoiseJit.BoundKernel chunking$onFlatCacheInit(Object flatCache, DensityFunction filler) {
        if (this.chunking$plan != null) {
            List<Object> flats = this.chunking$flatCaches;
            if (flats == null) {
                flats = new java.util.ArrayList<>(20);
                this.chunking$flatCaches = flats;
            }
            int ord = flats.size();
            flats.add(flatCache);
            List<Object> c2ds = this.chunking$cache2Ds != null
                    ? this.chunking$cache2Ds : java.util.Collections.emptyList();
            return this.chunking$plan.resolve(
                    NoiseJit.PLAN_FLAT_CACHE, ord, filler,
                    flats, c2ds, this.interpolators, this.beardifier);
        }
        return NoiseJit.compileAndBind(filler);
    }

    @Override
    public NoiseJit.BoundKernel chunking$resolveSliceKernel(
            NoiseChunk.NoiseInterpolator interp,
            DensityFunction fn
    ) {
        if (this.chunking$plan != null) {
            List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
            for (int i = 0, n = interps.size(); i < n; i++) {
                if (interps.get(i) == interp) {
                    List<Object> flats = this.chunking$flatCaches != null
                            ? this.chunking$flatCaches : java.util.Collections.emptyList();
                    List<Object> c2ds = this.chunking$cache2Ds != null
                            ? this.chunking$cache2Ds : java.util.Collections.emptyList();
                    return this.chunking$plan.resolve(
                            NoiseJit.PLAN_INTERP_SLICE, i, fn,
                            flats, c2ds, interps, this.beardifier);
                }
            }
        }
        return chunking$resolveJitKernel(fn);
    }

    @Override
    public int chunking$resolveInterpolatorOrdinal(NoiseChunk.NoiseInterpolator interp) {
        List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
        if (interps == null) {
            return -1;
        }
        for (int i = 0, n = interps.size(); i < n; i++) {
            if (interps.get(i) == interp) {
                return i;
            }
        }
        return -1;
    }

    @Unique
    private static final double[] CHUNKING$ZERO_SLICE = new double[128];

    @Unique
    private byte chunking$borderSliceEligible;

    @Unique
    private boolean chunking$canShareBorderSlice(int bx, int bz, int interpOrdinal) {
        if (interpOrdinal < 0 || interpOrdinal >= 256
                || ((bx & 15) != 0 && (bz & 15) != 0)
                || (bx & 3) != 0 || (bz & 3) != 0) {
            return false;
        }
        byte eligible = this.chunking$borderSliceEligible;
        if (eligible == 0) {
            boolean ok = this.chunking$sharedBorderSliceCache != null
                    && (this.blender == null || this.blender.isEmpty())
                    && com.chunking.engine.world.ModCompat.isEffectivelyEmptyBeardifier(this.beardifier);
            eligible = ok ? (byte) 1 : (byte) -1;
            this.chunking$borderSliceEligible = eligible;
        }
        return eligible > 0;
    }

    @Unique
    private long chunking$packBorderSliceKey(int bx, int bz, int interpOrdinal) {
        int qx = bx >> 2;
        int qz = bz >> 2;
        int meta = ((this.cellNoiseMinY & 0xF) << 4) | (this.cellHeight & 0xF);
        return ((long) qx & 0xFFFFFFL)
                | (((long) qz & 0xFFFFFFL) << 24)
                | (((long) interpOrdinal & 0xFFL) << 48)
                | (((long) meta & 0xFFL) << 56);
    }

    @Override
    public boolean chunking$evalVerticalSliceAt(
            double[] out,
            NoiseJit.BoundKernel bound,
            int bx,
            int bz,
            int maxCount
    ) {
        return chunking$evalVerticalSliceAtCached(out, bound, bx, bz, maxCount, -1);
    }

    @Override
    public boolean chunking$evalVerticalSliceAtCached(
            double[] out,
            NoiseJit.BoundKernel bound,
            int bx,
            int bz,
            int maxCount,
            int interpOrdinal
    ) {
        if (!ModConfig.SIMD_NOISE || bound == null || out == null || out.length == 0) {
            return false;
        }
        int count = maxCount > 0 ? Math.min(this.cellCountY + 1, maxCount) : (this.cellCountY + 1);
        if (out.length < count) {
            return false;
        }
        boolean cacheable = chunking$canShareBorderSlice(bx, bz, interpOrdinal)
                && bx >= -32_000_000 && bx <= 32_000_000 && bz >= -32_000_000 && bz <= 32_000_000;
        long cacheKey = 0L;
        if (cacheable) {
            cacheKey = chunking$packBorderSliceKey(bx, bz, interpOrdinal);
            if (chunking$lookupBorderSlice(cacheKey, count, out)) {
                return true;
            }
        }
        SliceCoordScratch sc = CHUNKING$SLICE_COORDS_TL.get();
        sc.ensure(count);
        int minYCell = this.cellNoiseMinY;
        int ch = this.cellHeight;
        int[] bxc = sc.bx;
        int[] byc = sc.by;
        int[] bzc = sc.bz;
        for (int i = 0; i < count; i++) {
            bxc[i] = bx;
            byc[i] = (i + minYCell) * ch;
            bzc[i] = bz;
        }
        bound.evalBatch(bxc, byc, bzc, out, count, (NoiseChunk) (Object) this, true);
        if (cacheable) {
            chunking$storeBorderSlice(cacheKey, count, out);
        }
        return true;
    }

    @Unique
    private boolean chunking$lookupBorderSlice(long cacheKey, int count, double[] out) {
        var cache = this.chunking$sharedBorderSliceCache;
        long mixed = it.unimi.dsi.fastutil.HashCommon.mix(cacheKey);
        int mask = com.chunking.engine.access.RandomStateAccess.BORDER_SLICE_CACHE_MASK;
        int minYCell = this.cellNoiseMinY;
        int ch = this.cellHeight;
        int slot0 = ((int) mixed) & mask;
        var hit = cache.getAcquire(slot0);
        if (hit != null && hit.key() == cacheKey && hit.count() == count
                && hit.minYCell() == minYCell && hit.cellHeight() == ch) {
            System.arraycopy(hit.values(), 0, out, 0, count);
            return true;
        }
        int slot1 = ((int) (mixed >>> 16)) & mask;
        if (slot1 != slot0) {
            hit = cache.getAcquire(slot1);
            if (hit != null && hit.key() == cacheKey && hit.count() == count
                    && hit.minYCell() == minYCell && hit.cellHeight() == ch) {
                System.arraycopy(hit.values(), 0, out, 0, count);
                return true;
            }
        }
        return false;
    }

    @Unique
    private void chunking$storeBorderSlice(long cacheKey, int count, double[] out) {
        var cache = this.chunking$sharedBorderSliceCache;
        long mixed = it.unimi.dsi.fastutil.HashCommon.mix(cacheKey);
        int mask = com.chunking.engine.access.RandomStateAccess.BORDER_SLICE_CACHE_MASK;
        int minYCell = this.cellNoiseMinY;
        int ch = this.cellHeight;
        int slot0 = ((int) mixed) & mask;
        int slot1 = ((int) (mixed >>> 16)) & mask;
        var prev0 = cache.getAcquire(slot0);
        if (prev0 != null) {
            if (prev0.key() == cacheKey && prev0.count() == count
                    && prev0.minYCell() == minYCell && prev0.cellHeight() == ch) {
                return;
            }
            if (slot1 != slot0) {
                var prev1 = cache.getAcquire(slot1);
                if (prev1 != null && prev1.key() == cacheKey && prev1.count() == count
                        && prev1.minYCell() == minYCell && prev1.cellHeight() == ch) {
                    return;
                }
                cache.setRelease(slot1, prev0);
            }
        }
        double[] storedValues = null;
        if (count <= CHUNKING$ZERO_SLICE.length && out[0] == 0.0D) {
            boolean allZero = true;
            for (int i = 1; i < count; i++) {
                if (out[i] != 0.0D) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                storedValues = CHUNKING$ZERO_SLICE;
            }
        }
        if (storedValues == null) {
            storedValues = new double[count];
            System.arraycopy(out, 0, storedValues, 0, count);
        }
        cache.setRelease(
                slot0,
                new com.chunking.engine.access.RandomStateAccess.BorderSliceEntry(
                        cacheKey, count, minYCell, ch, storedValues));
    }

    @Override
    public boolean chunking$evalVerticalSlice(double[] out, NoiseJit.BoundKernel bound) {
        return chunking$evalVerticalSliceCached(out, bound, -1);
    }

    @Override
    public boolean chunking$evalVerticalSliceCached(
            double[] out,
            NoiseJit.BoundKernel bound,
            int interpOrdinal
    ) {
        if (!ModConfig.SIMD_NOISE || bound == null || out == null || out.length == 0) {
            return false;
        }
        int cap = this.chunking$maxSliceCount;
        int count = cap > 0 ? Math.min(this.cellCountY + 1, cap) : (this.cellCountY + 1);
        if (out.length < count) {
            return false;
        }
        int bx = this.cellStartBlockX;
        int bz = this.cellStartBlockZ;
        int minYCell = this.cellNoiseMinY;
        int ch = this.cellHeight;
        boolean cacheable = chunking$canShareBorderSlice(bx, bz, interpOrdinal)
                && bx >= -32_000_000 && bx <= 32_000_000 && bz >= -32_000_000 && bz <= 32_000_000;
        long cacheKey = 0L;
        if (cacheable) {
            cacheKey = chunking$packBorderSliceKey(bx, bz, interpOrdinal);
            if (chunking$lookupBorderSlice(cacheKey, count, out)) {
                this.cellStartBlockY = ((count - 1) + minYCell) * ch;
                this.interpolationCounter += count;
                this.inCellY = 0;
                this.arrayIndex = count - 1;
                return true;
            }
        }
        SliceCoordScratch sc = CHUNKING$SLICE_COORDS_TL.get();
        sc.ensure(count);
        int[] bxc = sc.bx;
        int[] byc = sc.by;
        int[] bzc = sc.bz;
        for (int i = 0; i < count; i++) {
            bxc[i] = bx;
            byc[i] = (i + minYCell) * ch;
            bzc[i] = bz;
        }
        bound.evalBatch(bxc, byc, bzc, out, count, (NoiseChunk) (Object) this, true);
        if (cacheable) {
            chunking$storeBorderSlice(cacheKey, count, out);
        }
        this.cellStartBlockY = ((count - 1) + minYCell) * ch;
        this.interpolationCounter += count;
        this.inCellY = 0;
        this.arrayIndex = count - 1;
        return true;
    }

    @Override
    public boolean chunking$tryFillVerticalSlice(double[] out, DensityFunction function) {
        if (!ModConfig.SIMD_NOISE || out == null || out.length == 0) {
            return false;
        }
        return chunking$evalVerticalSlice(out, chunking$resolveJitKernel(function));
    }

    @Override
    public void chunking$setMaxSliceCount(int maxSliceCount) {
        this.chunking$maxSliceCount = maxSliceCount;
    }

    @Override
    public int chunking$getMaxSliceCount() {
        int cap = this.chunking$maxSliceCount;
        return cap > 0 ? cap : Integer.MAX_VALUE;
    }

    @Override
    public int chunking$getOreVeinSliceCount(int fullLength) {
        com.chunking.engine.mixin.accessor.NoiseChunkAccessor acc =
                (com.chunking.engine.mixin.accessor.NoiseChunkAccessor) this;
        int ch = Math.max(1, acc.chunking$getCellHeight());
        int minBlockY = acc.chunking$getCellNoiseMinY() * ch;
        int maxOreCornerY = Math.max(64, ((com.chunking.engine.world.FastOreVeins.MAX_VEIN_Y + ch) / ch) * ch);
        if (minBlockY > maxOreCornerY) {
            return 1;
        }
        int needed = ((maxOreCornerY - minBlockY) / ch) + 1;
        return Math.min(fullLength, Math.max(1, needed));
    }

    @Inject(method = "fillAllDirectly", at = @At("HEAD"), cancellable = true)
    private void chunking$simdFillAllDirectly(double[] out, DensityFunction function, CallbackInfo ci) {
        if (!ModConfig.SIMD_NOISE || out == null || out.length < 16) {
            return;
        }
        int cw = this.cellWidth;
        int ch = this.cellHeight;
        int total = ch * cw * cw;
        if (total < 16 || total > out.length) {
            return;
        }
        NoiseJit.BoundKernel bound = chunking$resolveJitKernel(function);
        if (bound == null) {
            return;
        }

        SliceCoordScratch sc = CHUNKING$SLICE_COORDS_TL.get();
        sc.ensure(total);
        int[] bxc = sc.bx;
        int[] byc = sc.by;
        int[] bzc = sc.bz;

        int baseX = this.cellStartBlockX;
        int baseY = this.cellStartBlockY;
        int baseZ = this.cellStartBlockZ;
        int idx = 0;
        for (int y = ch - 1; y >= 0; y--) {
            int wy = baseY + y;
            for (int x = 0; x < cw; x++) {
                int wx = baseX + x;
                for (int z = 0; z < cw; z++) {
                    bxc[idx] = wx;
                    byc[idx] = wy;
                    bzc[idx] = baseZ + z;
                    idx++;
                }
            }
        }

        bound.evalBatch(bxc, byc, bzc, out, total, (NoiseChunk) (Object) this, false);

        this.inCellY = 0;
        this.inCellX = cw - 1;
        this.inCellZ = cw - 1;
        this.arrayIndex = total;
        this.interpolationCounter += total;
        ci.cancel();
    }

    @Unique
    private DensityFunction chunking$vtFn;

    @Unique
    private DensityFunction chunking$vrFn;

    @Unique
    private DensityFunction chunking$vgFn;

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"
            ),
            index = 0,
            require = 0
    )
    private DensityFunction chunking$captureVeinToggle(DensityFunction veinToggle) {
        this.chunking$vtFn = veinToggle;
        if (veinToggle instanceof CacheAllInCellAccess vt) {
            this.chunking$veinToggleCell = vt;
        }
        this.chunking$veinToggleInterp = com.chunking.engine.simd.JitSupport.findExactOreToggleInterpolator(veinToggle);
        return veinToggle;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"
            ),
            index = 1,
            require = 0
    )
    private DensityFunction chunking$captureVeinRidged(DensityFunction veinRidged) {
        this.chunking$vrFn = veinRidged;
        if (veinRidged instanceof CacheAllInCellAccess vr) {
            this.chunking$veinRidgedCell = vr;
        }
        return veinRidged;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"
            ),
            index = 2,
            require = 0
    )
    private DensityFunction chunking$captureVeinGap(DensityFunction veinGap) {
        this.chunking$vgFn = veinGap;
        if (veinGap instanceof CacheAllInCellAccess vg) {
            this.chunking$veinGapCell = vg;
        }
        return veinGap;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"
            ),
            index = 3,
            require = 0
    )
    private net.minecraft.world.level.levelgen.PositionalRandomFactory chunking$captureOreRandom(
            net.minecraft.world.level.levelgen.PositionalRandomFactory oreRandom) {
        this.chunking$oreRandom = oreRandom;
        return oreRandom;
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/RandomState;router()Lnet/minecraft/world/level/levelgen/NoiseRouter;"
            ),
            require = 0
    )
    private net.minecraft.world.level.levelgen.NoiseRouter chunking$captureSharedPrelimCache(
            net.minecraft.world.level.levelgen.RandomState randomState,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.level.levelgen.NoiseRouter> original) {
        if (ModConfig.SIMD_NOISE && (this.blender == null || this.blender.isEmpty())
                && (Object) randomState instanceof com.chunking.engine.access.RandomStateAccess rsAcc) {
            this.chunking$sharedPrelimCache = rsAcc.chunking$prelimSurfaceCache();
            this.chunking$sharedBorderSliceCache = rsAcc.chunking$borderSliceCache();
        }
        return original.call(randomState);
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"
            ),
            require = 0
    )
    private net.minecraft.world.level.levelgen.NoiseRouter chunking$captureWrappedRouter(
            net.minecraft.world.level.levelgen.NoiseRouter router,
            DensityFunction.Visitor visitor,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.level.levelgen.NoiseRouter> original) {
        boolean scope = ModConfig.SIMD_NOISE && (this.blender == null || this.blender.isEmpty());
        this.chunking$initRouter = router;
        if (scope) {
            this.chunking$plan = NoiseJit.getOrCreatePlan(router);
            if (this.chunking$flatCaches == null) {
                this.chunking$flatCaches = new java.util.ArrayList<>(20);
            }
            if (this.chunking$cache2Ds == null) {
                this.chunking$cache2Ds = new java.util.ArrayList<>(8);
            }
        }
        net.minecraft.world.level.levelgen.NoiseRouter wrapped;
        if (scope) {
            com.chunking.engine.world.RouterCache.beginNewChunk();
            try {
                wrapped = original.call(router, visitor);
                com.chunking.engine.world.RouterCache.seedIdempotent(wrapped.finalDensity());
            } finally {
                com.chunking.engine.world.RouterCache.pauseScope();
            }
        } else {
            wrapped = original.call(router, visitor);
        }
        this.chunking$wrappedRouter = wrapped;
        return wrapped;
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"
            ),
            require = 0
    )
    private DensityFunction chunking$wrapFullNoiseWithCache(
            DensityFunction fn,
            DensityFunction.Visitor visitor,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<DensityFunction> original) {
        DensityFunction res;
        if (ModConfig.SIMD_NOISE && (this.blender == null || this.blender.isEmpty())) {
            com.chunking.engine.world.RouterCache.resumeScope();
            try {
                res = original.call(fn, visitor);
            } finally {
                com.chunking.engine.world.RouterCache.pauseScope();
            }
        } else {
            res = original.call(fn, visitor);
        }
        return res;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$endRouterWrapCacheScopeOnInitReturn(CallbackInfo ci) {
        com.chunking.engine.world.RouterCache.endNewChunk();
    }

    @Inject(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;wrapped:Ljava/util/Map;",
                    opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void chunking$useIdentityWrappedMap(CallbackInfo ci) {
        if (ModConfig.SIMD_NOISE) {
            this.wrapped = new IdentityHashMap<>(128);
        }
    }

    /**
     * @author Chunking
     * @reason Identity-bypass DensityFunction.wrap and avoid Map.computeIfAbsent / @WrapMethod lambda allocations.
     */
    @Overwrite
    protected DensityFunction wrap(DensityFunction df) {
        if (ModConfig.SIMD_NOISE
                && (this.blender == null || this.blender.isEmpty())
                && com.chunking.engine.world.ModCompat.isVanillaClass(df.getClass())
                && !(df instanceof net.minecraft.world.level.levelgen.DensityFunctions.MarkerOrMarked)
                && !(df instanceof net.minecraft.world.level.levelgen.DensityFunctions.HolderHolder)
                && !(df instanceof net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker)) {
            return df;
        }
        DensityFunction existing = this.wrapped.get(df);
        if (existing != null) {
            return existing;
        }
        DensityFunction created = this.wrapNew(df);
        this.wrapped.put(df, created);
        return created;
    }

    @Inject(method = "wrapNew", at = @At("HEAD"))
    private void chunking$trackHolderHolder(
            DensityFunction df,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<DensityFunction> cir
    ) {
        if (ModConfig.SIMD_NOISE && df instanceof net.minecraft.world.level.levelgen.DensityFunctions.HolderHolder) {
            com.chunking.engine.world.RouterCache.markHolderHolder();
        }
    }

    @Inject(method = "wrapNew", at = @At("RETURN"))
    private void chunking$trackCache2D(
            DensityFunction df,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<DensityFunction> cir
    ) {
        if (this.chunking$plan != null
                && cir.getReturnValue() instanceof com.chunking.engine.access.Cache2DAccess c2d) {
            List<Object> list = this.chunking$cache2Ds;
            if (list == null) {
                list = new java.util.ArrayList<>(8);
                this.chunking$cache2Ds = list;
            }
            list.add(c2d);
        }
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "preliminarySurfaceLevel")
    private int chunking$fastSharedPrelimSurfaceLevel(
            int bx,
            int bz,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original
    ) {
        java.util.concurrent.atomic.AtomicLongArray cache = this.chunking$sharedPrelimCache;
        if (cache == null) {
            return original.call(bx, bz);
        }
        int qx = net.minecraft.core.QuartPos.fromBlock(bx);
        int qz = net.minecraft.core.QuartPos.fromBlock(bz);
        if (qx < -16_000_000 || qx > 16_000_000 || qz < -16_000_000 || qz > 16_000_000) {
            return original.call(bx, bz);
        }
        int slot = (qx * 73856093 ^ qz * 19349663) & 16383;
        long p = cache.getOpaque(slot);
        if ((p & 1L) != 0L) {
            int cQx = (int) (p >> 39);
            int cQz = (int) ((p << 25) >> 39);
            if (cQx == qx && cQz == qz) {
                return (int) ((p << 50) >> 51);
            }
        }
        int level = this.computePreliminarySurfaceLevel(
                net.minecraft.server.level.ColumnPos.asLong(
                        net.minecraft.core.QuartPos.toBlock(qx),
                        net.minecraft.core.QuartPos.toBlock(qz)));
        if (level >= -4096 && level <= 4095) {
            long packed = ((long) qx << 39)
                    | (((long) qz & 0x1FFFFFFL) << 14)
                    | (((long) level & 0x1FFFL) << 1)
                    | 1L;
            cache.setOpaque(slot, packed);
        }
        return level;
    }

    @Unique
    private boolean chunking$bypassClimateMapAll;

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "cachedClimateSampler",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"
            )
    )
    private DensityFunction chunking$skipWrappedClimateMapAll(
            DensityFunction df,
            DensityFunction.Visitor visitor,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<DensityFunction> original
    ) {
        if (this.chunking$bypassClimateMapAll) {
            return df;
        }
        return original.call(df, visitor);
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "cachedClimateSampler")
    private net.minecraft.world.level.biome.Climate.Sampler chunking$reuseWrappedClimateSampler(
            net.minecraft.world.level.levelgen.NoiseRouter router,
            java.util.List<net.minecraft.world.level.biome.Climate.ParameterPoint> points,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.level.biome.Climate.Sampler> original
    ) {
        if (!ModConfig.SIMD_NOISE || this.chunking$wrappedRouter == null || this.chunking$initRouter != router) {
            return original.call(router, points);
        }
        net.minecraft.world.level.biome.Climate.Sampler cached = this.chunking$cachedSampler;
        if (cached == null) {
            this.chunking$bypassClimateMapAll = true;
            try {
                cached = original.call(this.chunking$wrappedRouter, points);
            } finally {
                this.chunking$bypassClimateMapAll = false;
            }
            this.chunking$cachedSampler = cached;
        }
        return cached;
    }

    @Unique
    private NoiseInterpolatorAccess[] chunking$nonOreSelectArr;

    @Unique
    private NoiseInterpolatorAccess[] chunking$oreSelectArr;

    @Unique
    private int chunking$coarseBlockX;

    @Unique
    private int chunking$coarseCellZ;

    @Unique
    private int chunking$coarseMinCellY;

    @Unique
    private int chunking$coarseMaxCellY;

    @Unique
    private byte chunking$coarseCls;

    @Unique
    private byte chunking$onlyStdCellCaches;

    @Unique
    private static final ThreadLocal<double[]> CHUNKING$VT_RANGE_TL =
            ThreadLocal.withInitial(() -> new double[2]);

    @Unique
    private boolean chunking$hasOnlyStandardCellCaches(CacheAllInCellAccess fullCell) {
        byte flag = this.chunking$onlyStdCellCaches;
        if (flag != 0) {
            return flag > 0;
        }
        List<?> caches = this.cellCaches;
        boolean ok = fullCell != null;
        if (ok) {
            for (int i = 0, n = caches.size(); i < n; i++) {
                Object o = caches.get(i);
                if (o != fullCell && o != this.chunking$veinToggleCell
                        && o != this.chunking$veinRidgedCell && o != this.chunking$veinGapCell) {
                    ok = false;
                    break;
                }
            }
        }
        this.chunking$onlyStdCellCaches = ok ? (byte) 1 : (byte) -1;
        return ok;
    }

    @Unique
    private boolean chunking$isRangeOreInactive(int rangeStartY, int rangeEndY, int cellZ, int cellYLo, int cellYHi) {
        if (SharedConstants.DEBUG_ORE_VEINS) {
            return false;
        }
        if (this.chunking$oreRandom == null && this.chunking$vtFn == null) {
            return true;
        }
        if (com.chunking.engine.world.FastOreVeins.isRangeOutsideOreVeinY(rangeStartY, rangeEndY)) {
            return true;
        }
        if (!com.chunking.engine.world.FastOreVeins.IS_VANILLA_VEINS) {
            return false;
        }
        NoiseInterpolatorAccess vtInterp = this.chunking$veinToggleInterp;
        if (vtInterp == null) {
            return false;
        }
        double[] minMax = CHUNKING$VT_RANGE_TL.get();
        if (!CellSkipper.interpRangeBounds(vtInterp, cellZ, cellYLo, cellYHi, minMax)) {
            return false;
        }
        double copperThr = chunking$copperMinToggleForRange(rangeStartY, rangeEndY);
        if (copperThr <= 1.0D && minMax[1] >= copperThr) {
            return false;
        }
        double ironThr = chunking$ironMinAbsToggleForRange(rangeStartY, rangeEndY);
        if (ironThr <= 1.0D && minMax[0] <= -ironThr) {
            return false;
        }
        return true;
    }

    @Unique
    private boolean chunking$isGlobalFluidAirRangeAt(int x0, int z0, int minY, int maxY) {
        if (SharedConstants.DEBUG_AQUIFERS || !(this.aquifer instanceof AquiferAccess aq)) {
            return false;
        }
        if (minY <= aq.chunking$skipSamplingAboveY()) {
            return false;
        }
        return aq.chunking$isGlobalFluidAirRange(x0, minY, maxY, z0)
                && aq.chunking$isGlobalFluidAirRange(x0 + this.cellWidth - 1, minY, maxY, z0 + this.cellWidth - 1);
    }

    @Unique
    private boolean chunking$tryCoarseSkip(int cellY, int cellZ, CacheAllInCellAccess fullCell) {
        if (!this.chunking$vanillaFillSlice
                || this.cellCountXZ <= 1
                || SharedConstants.DEBUG_ORE_VEINS
                || SharedConstants.DEBUG_AQUIFERS
                || !chunking$hasOnlyStandardCellCaches(fullCell)) {
            return false;
        }
        int curBlockX = this.cellStartBlockX;
        boolean inBlock = cellZ == this.chunking$coarseCellZ
                && curBlockX == this.chunking$coarseBlockX
                && cellY >= this.chunking$coarseMinCellY
                && cellY <= this.chunking$coarseMaxCellY;
        if (inBlock) {
            byte cachedCls = this.chunking$coarseCls;
            if (cachedCls == 0) {
                return false;
            }
            this.cellStartBlockY = (cellY + this.cellNoiseMinY) * this.cellHeight;
            this.cellStartBlockZ = (this.firstCellZ + cellZ) * this.cellWidth;
            this.arrayInterpolationCounter += 2;
            this.fillingCell = false;
            if (cachedCls < 0) {
                if (!this.chunking$lastCellAllNonPositive) {
                    java.util.Arrays.fill(fullCell.chunking$cellValues(), -1.0D);
                }
                this.chunking$lastCellAllNonPositive = true;
                this.chunking$lastCellAllPositive = false;
            } else {
                if (!this.chunking$lastCellAllPositive) {
                    java.util.Arrays.fill(fullCell.chunking$cellValues(), 1.0D);
                }
                this.chunking$lastCellAllNonPositive = false;
                this.chunking$lastCellAllPositive = true;
            }
            this.chunking$lastCellOreInactive = true;
            return true;
        }
        // Only probe a new 16-block coarse band at column top or after a uniform cell; avoids wasting
        // classifyRange + classifyCell double-evals throughout mixed surface/cave bands.
        if (cellY < 1 || (cellY != this.cellCountY - 1
                && !this.chunking$lastCellAllNonPositive
                && !this.chunking$lastCellAllPositive)) {
            return false;
        }
        CellSkipper est = this.chunking$cellEstimator;
        if (est == null && !this.chunking$checkedEstimator) {
            this.chunking$checkedEstimator = true;
            Blender bl = ((NoiseChunkAccessor) (Object) this).chunking$getBlender();
            est = CellSkipper.compile(
                    this.chunking$initRouter,
                    this.interpolators,
                    this.chunking$flatCaches,
                    fullCell.chunking$cellNoiseFiller(),
                    bl == null || bl.isEmpty(),
                    this.beardifier,
                    this.cellWidth);
            this.chunking$cellEstimator = est;
        }
        if (est == null || est.hasUnverifiedModBounds()) {
            return false;
        }
        int span = Math.max(2, Math.min(8, 16 / Math.max(1, this.cellHeight)));
        int cellYLo = Math.max(0, cellY - span + 1);
        int rangeStartY = (cellYLo + this.cellNoiseMinY) * this.cellHeight;
        int rangeEndY = (cellY + this.cellNoiseMinY + 1) * this.cellHeight - 1;
        int blockZ = (this.firstCellZ + cellZ) * this.cellWidth;
        this.chunking$coarseBlockX = curBlockX;
        this.chunking$coarseCellZ = cellZ;
        this.chunking$coarseMinCellY = cellYLo;
        this.chunking$coarseMaxCellY = cellY;
        this.chunking$coarseCls = 0;
        est.ensureBound(this, this.interpolators, this.chunking$flatCaches, this.beardifier);
        int rCls = est.classifyRange(curBlockX, rangeStartY, rangeEndY, blockZ, cellZ, cellYLo, cellY);
        if (rCls < 0 && chunking$isGlobalFluidAirRangeAt(curBlockX, blockZ, rangeStartY, rangeEndY)) {
            this.chunking$coarseCls = -1;
            this.cellStartBlockY = (cellY + this.cellNoiseMinY) * this.cellHeight;
            this.cellStartBlockZ = blockZ;
            this.arrayInterpolationCounter += 2;
            this.fillingCell = false;
            if (!this.chunking$lastCellAllNonPositive) {
                java.util.Arrays.fill(fullCell.chunking$cellValues(), -1.0D);
            }
            this.chunking$lastCellAllNonPositive = true;
            this.chunking$lastCellAllPositive = false;
            this.chunking$lastCellOreInactive = true;
            return true;
        }
        if (rCls > 0 && chunking$isRangeOreInactive(rangeStartY, rangeEndY, cellZ, cellYLo, cellY)) {
            this.chunking$coarseCls = 1;
            this.cellStartBlockY = (cellY + this.cellNoiseMinY) * this.cellHeight;
            this.cellStartBlockZ = blockZ;
            this.arrayInterpolationCounter += 2;
            this.fillingCell = false;
            if (!this.chunking$lastCellAllPositive) {
                java.util.Arrays.fill(fullCell.chunking$cellValues(), 1.0D);
            }
            this.chunking$lastCellAllNonPositive = false;
            this.chunking$lastCellAllPositive = true;
            this.chunking$lastCellOreInactive = true;
            return true;
        }
        return false;
    }

    /**
     * @author Chunking
     * @reason Fast array-backed cell selection and fused cache evaluation without @Inject CallbackInfo allocation.
     */
    @Overwrite
    public void selectCellYZ(int cellY, int cellZ) {
        if (!ModConfig.SIMD_NOISE) {
            List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
            for (int i = 0, n = interps.size(); i < n; i++) {
                ((NoiseInterpolatorAccess) interps.get(i)).chunking$selectCellYZ(cellY, cellZ);
            }
            this.fillingCell = true;
            this.cellStartBlockY = (cellY + this.cellNoiseMinY) * this.cellHeight;
            this.cellStartBlockZ = (this.firstCellZ + cellZ) * this.cellWidth;
            this.arrayInterpolationCounter++;
            DensityFunction.ContextProvider self = (DensityFunction.ContextProvider) this;
            List<?> caches = this.cellCaches;
            for (int i = 0, n = caches.size(); i < n; i++) {
                CacheAllInCellAccess cell = (CacheAllInCellAccess) caches.get(i);
                cell.chunking$cellNoiseFiller().fillArray(cell.chunking$cellValues(), self);
            }
            this.arrayInterpolationCounter++;
            this.fillingCell = false;
            return;
        }
        this.chunking$activeVtValues = null;
        CacheAllInCellAccess fullCell = this.chunking$fullNoiseCell;
        if (fullCell == null && this.fullNoiseDensity instanceof CacheAllInCellAccess fc) {
            fullCell = fc;
            this.chunking$fullNoiseCell = fc;
        }
        if ((this.cellHeight <= 4 || this.cellCountY >= 64) && chunking$tryCoarseSkip(cellY, cellZ, fullCell)) {
            return;
        }
        NoiseInterpolatorAccess[] nonOreArr = this.chunking$nonOreSelectArr;
        NoiseInterpolatorAccess[] oreArr = this.chunking$oreSelectArr;
        if (nonOreArr == null) {
            List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
            int n = interps.size();
            java.util.ArrayList<NoiseInterpolatorAccess> nonOreL = new java.util.ArrayList<>(n);
            java.util.ArrayList<NoiseInterpolatorAccess> oreL = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                NoiseInterpolatorAccess acc = (NoiseInterpolatorAccess) interps.get(i);
                if (acc.chunking$isUnusedInNoiseFill()) {
                    continue;
                }
                if (acc.chunking$isOreVeinOnly()) {
                    oreL.add(acc);
                } else {
                    nonOreL.add(acc);
                }
            }
            nonOreArr = nonOreL.toArray(NoiseInterpolatorAccess[]::new);
            oreArr = oreL.toArray(NoiseInterpolatorAccess[]::new);
            this.chunking$nonOreSelectArr = nonOreArr;
            this.chunking$oreSelectArr = oreArr;
        }
        if (!this.chunking$vanillaFillSlice || SharedConstants.DEBUG_ORE_VEINS || SharedConstants.DEBUG_AQUIFERS) {
            List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
            for (int i = 0, n = interps.size(); i < n; i++) {
                ((NoiseInterpolatorAccess) interps.get(i)).chunking$selectCellYZ(cellY, cellZ);
            }
        } else {
            for (int i = 0; i < nonOreArr.length; i++) {
                nonOreArr[i].chunking$selectCellYZ(cellY, cellZ);
            }
        }
        this.fillingCell = true;
        int startY = (cellY + this.cellNoiseMinY) * this.cellHeight;
        int endY = startY + this.cellHeight - 1;
        this.cellStartBlockY = startY;
        this.cellStartBlockZ = (this.firstCellZ + cellZ) * this.cellWidth;
        this.arrayInterpolationCounter++;

        DensityFunction.ContextProvider self = (DensityFunction.ContextProvider) this;
        List<?> caches = this.cellCaches;
        boolean allNonPos = false;
        boolean allPos = false;
        boolean sawFullDensity = false;

        boolean oreInactive = chunking$computeOreInactive(startY, endY, cellY, cellZ);
        boolean globalFluidAir = chunking$isGlobalFluidAirCell(startY, endY);
        int cls = 0;
        if (fullCell != null) {
            double[] fullVals = fullCell.chunking$cellValues();
            CellSkipper est = null;
            if (this.chunking$vanillaFillSlice) {
                est = this.chunking$cellEstimator;
                if (est == null && !this.chunking$checkedEstimator) {
                    this.chunking$checkedEstimator = true;
                    Blender bl = ((NoiseChunkAccessor) (Object) this).chunking$getBlender();
                    est = CellSkipper.compile(
                            this.chunking$initRouter,
                            this.interpolators,
                            this.chunking$flatCaches,
                            fullCell.chunking$cellNoiseFiller(),
                            bl == null || bl.isEmpty(),
                            this.beardifier,
                            this.cellWidth);
                    this.chunking$cellEstimator = est;
                }
            }
            boolean probeModBounds = false;
            if (est != null) {
                est.ensureBound(this, this.interpolators, this.chunking$flatCaches, this.beardifier);
                cls = est.classifyCell(this.cellStartBlockX, startY, this.cellStartBlockZ, this.cellHeight);
                probeModBounds = (cls != 0 && est.hasUnverifiedModBounds());
            } else {
                cls = 0;
            }
            if (!probeModBounds && cls < 0) {
                boolean canSkipCellEval = globalFluidAir;
                if (!canSkipCellEval && !SharedConstants.DEBUG_AQUIFERS && this.aquifer instanceof AquiferAccess aq) {
                    BlockState uFluid = aq.chunking$getUniformBoxFluid(
                            this.cellStartBlockX, startY, this.cellStartBlockZ, this.cellWidth, this.cellHeight);
                    canSkipCellEval = (uFluid != null || aq.chunking$isPreparedAllEqual());
                }
                if (canSkipCellEval) {
                    sawFullDensity = true;
                    allNonPos = true;
                    allPos = false;
                    if (this.cellCountXZ == 1 || !globalFluidAir || !this.chunking$lastCellAllNonPositive) {
                        java.util.Arrays.fill(fullVals, -1.0D);
                    }
                } else {
                    cls = 0;
                }
            }
            if (!probeModBounds && cls > 0) {
                sawFullDensity = true;
                allNonPos = false;
                allPos = true;
                java.util.Arrays.fill(fullVals, 1.0D);
            } else if (!sawFullDensity) {
                self.fillAllDirectly(fullVals, fullCell.chunking$cellNoiseFiller());
                sawFullDensity = true;
                if (probeModBounds) {
                    est.verifyCellValues(cls, fullVals);
                }
                boolean hasPos = false;
                boolean hasNonPos = false;
                for (int k = 0; k < fullVals.length; k++) {
                    if (fullVals[k] > 0.0D) {
                        hasPos = true;
                    } else {
                        hasNonPos = true;
                    }
                    if (hasPos && hasNonPos) {
                        break;
                    }
                }
                allNonPos = !hasPos;
                allPos = !hasNonPos;
            }
        }

        CacheAllInCellAccess vtCell = this.chunking$veinToggleCell;
        CacheAllInCellAccess vrCell = this.chunking$veinRidgedCell;
        boolean filledVr = false;
        if (allNonPos && !oreInactive && !SharedConstants.DEBUG_ORE_VEINS) {
            boolean noBarrier = globalFluidAir;
            if (!noBarrier && !SharedConstants.DEBUG_AQUIFERS && this.aquifer instanceof AquiferAccess aq) {
                noBarrier = aq.chunking$getUniformBoxFluid(
                        this.cellStartBlockX, startY, this.cellStartBlockZ, this.cellWidth, this.cellHeight) != null
                        || aq.chunking$isPreparedAllEqual();
            }
            if (noBarrier) {
                oreInactive = true;
            }
        }
        if (!oreInactive && vtCell != null) {
            NoiseChunk selfNc = (NoiseChunk) (Object) this;
            int x0 = this.cellStartBlockX;
            int x1 = x0 + this.cellWidth;
            int z0 = this.cellStartBlockZ;
            int z1 = z0 + this.cellWidth;
            for (int i = 0; i < oreArr.length; i++) {
                NoiseInterpolatorAccess oa = oreArr[i];
                if (oa.chunking$isDeferOreExtras()) {
                    oa.chunking$ensureCellCornersFilled(selfNc, x0, x1, z0, z1, cellZ);
                }
                oa.chunking$selectCellYZ(cellY, cellZ);
            }
            double[] vtVals = vtCell.chunking$cellValues();
            self.fillAllDirectly(vtVals, vtCell.chunking$cellNoiseFiller());
            this.chunking$activeVtValues = vtVals;
            int cw = this.cellWidth;
            int strideY = cw * cw;
            boolean anyCandidate = false;
            for (int k = 0; k < vtVals.length; k++) {
                int by = endY - (k / strideY);
                if (chunking$isVoxelOreToggleCandidate(vtVals[k], by)) {
                    anyCandidate = true;
                    break;
                }
            }
            if (!anyCandidate) {
                oreInactive = true;
            } else if (vrCell != null) {
                double[] vrVals = vrCell.chunking$cellValues();
                self.fillAllDirectly(vrVals, vrCell.chunking$cellNoiseFiller());
                filledVr = true;
                boolean anyOreRidged = false;
                for (int k = 0; k < vtVals.length; k++) {
                    if (vrVals[k] < 0.0D) {
                        int by = endY - (k / strideY);
                        if (chunking$isVoxelOreToggleCandidate(vtVals[k], by)) {
                            anyOreRidged = true;
                            break;
                        }
                    }
                }
                if (!anyOreRidged) {
                    oreInactive = true;
                }
            }
        } else if (!oreInactive && vtCell == null && !SharedConstants.DEBUG_ORE_VEINS && !SharedConstants.DEBUG_AQUIFERS
                && com.chunking.engine.world.FastOreVeins.IS_VANILLA_VEINS) {
            NoiseInterpolatorAccess vtInterp = this.chunking$veinToggleInterp;
            NoiseChunk selfNc = (NoiseChunk) (Object) this;
            if (vtInterp != null) {
                int cw = this.cellWidth;
                int ch = this.cellHeight;
                int totalVoxels = ch * cw * cw;
                double[] vtVals = com.chunking.engine.simd.JitSupport.fillCellInterpolator(
                        vtInterp, selfNc, 31, totalVoxels);
                if (vtVals != null) {
                    int strideY = cw * cw;
                    boolean anyCandidate = false;
                    for (int k = 0; k < totalVoxels; k++) {
                        int by = endY - (k / strideY);
                        if (chunking$isVoxelOreToggleCandidate(vtVals[k], by)) {
                            anyCandidate = true;
                            break;
                        }
                    }
                    if (!anyCandidate) {
                        oreInactive = true;
                    } else {
                        this.chunking$activeVtValues = vtVals;
                    }
                }
                if (!oreInactive) {
                    int x0 = this.cellStartBlockX;
                    int x1 = x0 + this.cellWidth;
                    int z0 = this.cellStartBlockZ;
                    int z1 = z0 + this.cellWidth;
                    for (int i = 0; i < oreArr.length; i++) {
                        NoiseInterpolatorAccess oa = oreArr[i];
                        if (oa != vtInterp) {
                            if (oa.chunking$isDeferOreExtras()) {
                                oa.chunking$ensureCellCornersFilled(selfNc, x0, x1, z0, z1, cellZ);
                            }
                            oa.chunking$selectCellYZ(cellY, cellZ);
                        }
                    }
                }
            } else {
                int x0 = this.cellStartBlockX;
                int x1 = x0 + this.cellWidth;
                int z0 = this.cellStartBlockZ;
                int z1 = z0 + this.cellWidth;
                for (int i = 0; i < oreArr.length; i++) {
                    NoiseInterpolatorAccess oa = oreArr[i];
                    if (oa.chunking$isDeferOreExtras()) {
                        oa.chunking$ensureCellCornersFilled(selfNc, x0, x1, z0, z1, cellZ);
                    }
                    oa.chunking$selectCellYZ(cellY, cellZ);
                }
            }
        }

        for (int i = 0, n = caches.size(); i < n; i++) {
            Object obj = caches.get(i);
            if (obj == fullCell || obj == vtCell) {
                continue;
            }
            if (filledVr && obj == vrCell) {
                continue;
            }
            if (oreInactive && (obj == vrCell || obj == this.chunking$veinGapCell)) {
                continue;
            }
            if (!SharedConstants.DEBUG_ORE_VEINS && obj == this.chunking$veinGapCell
                    && !MixinPlugin.isNoisiumLoaded()
                    && com.chunking.engine.world.FastOreVeins.IS_VANILLA_VEINS) {
                continue;
            }
            CacheAllInCellAccess cell = (CacheAllInCellAccess) obj;
            double[] vals = cell.chunking$cellValues();
            self.fillAllDirectly(vals, cell.chunking$cellNoiseFiller());
            if (!sawFullDensity && obj == this.fullNoiseDensity) {
                sawFullDensity = true;
                this.chunking$fullNoiseCell = cell;
                boolean hasPos = false;
                boolean hasNonPos = false;
                for (int k = 0; k < vals.length; k++) {
                    if (vals[k] > 0.0D) {
                        hasPos = true;
                    } else {
                        hasNonPos = true;
                    }
                    if (hasPos && hasNonPos) {
                        break;
                    }
                }
                allNonPos = !hasPos;
                allPos = !hasNonPos;
            }
        }

        this.chunking$lastCellAllNonPositive = sawFullDensity && allNonPos;
        this.chunking$lastCellAllPositive = sawFullDensity && allPos;
        this.chunking$lastCellOreInactive = oreInactive;
        this.arrayInterpolationCounter++;
        this.fillingCell = false;
    }

    @Unique
    private boolean chunking$isGlobalFluidAirCell(int minY, int maxY) {
        if (SharedConstants.DEBUG_AQUIFERS || !(this.aquifer instanceof AquiferAccess aq)) {
            return false;
        }
        if (minY <= aq.chunking$skipSamplingAboveY()) {
            return false;
        }
        int x0 = this.cellStartBlockX;
        int z0 = this.cellStartBlockZ;
        return aq.chunking$isGlobalFluidAirRange(x0, minY, maxY, z0)
                && aq.chunking$isGlobalFluidAirRange(x0 + this.cellWidth - 1, minY, maxY, z0 + this.cellWidth - 1);
    }

    @Override
    public boolean chunking$isCurrentCellGuaranteedAir() {
        return this.chunking$lastCellAllNonPositive
                && chunking$isGlobalFluidAirCell(this.cellStartBlockY, this.cellStartBlockY + this.cellHeight - 1);
    }

    @Override
    public boolean chunking$isCurrentCellAllNonPositive() {
        return this.chunking$lastCellAllNonPositive;
    }

    @Override
    public boolean chunking$isCurrentCellAllPositive() {
        return this.chunking$lastCellAllPositive;
    }

    @Override
    public boolean chunking$isCurrentCellGuaranteedSolidDefault() {
        return this.chunking$lastCellAllPositive
                && this.chunking$lastCellOreInactive
                && !SharedConstants.DEBUG_AQUIFERS;
    }

    @Override
    public boolean chunking$isCurrentCellOreInactive() {
        return this.chunking$lastCellOreInactive;
    }

    @Override
    public boolean chunking$canEvalFastOre() {
        return !this.chunking$lastCellOreInactive
                && com.chunking.engine.world.FastOreVeins.IS_VANILLA_VEINS
                && !SharedConstants.DEBUG_ORE_VEINS
                && this.chunking$oreRandom != null
                && (this.chunking$activeVtValues != null || this.chunking$vtFn != null)
                && (this.chunking$veinRidgedCell != null || this.chunking$vrFn != null)
                && (this.chunking$veinGapCell != null || this.chunking$vgFn != null);
    }

    @Override
    public BlockState chunking$evalOreVeinAt(
            int bx,
            int by,
            int bz,
            int inX,
            int inY,
            int inZ,
            int cellIdx,
            BlockState defaultBlock
    ) {
        double[] vtArr = this.chunking$activeVtValues;
        double vt;
        boolean coordsSet = false;
        if (vtArr != null) {
            vt = vtArr[cellIdx];
        } else {
            this.inCellX = inX;
            this.inCellY = inY;
            this.inCellZ = inZ;
            coordsSet = true;
            vt = this.chunking$vtFn.compute((DensityFunction.FunctionContext) this);
        }
        final int minY;
        final int maxY;
        if (vt > 0.0D) {
            minY = 0;
            maxY = 50;
        } else {
            minY = -60;
            maxY = -8;
        }
        int dMax = maxY - by;
        int dMin = by - minY;
        if (dMin < 0 || dMax < 0) {
            return defaultBlock;
        }
        double absVt = Math.abs(vt);
        int minDist = Math.min(dMax, dMin);
        double edgeRoundoff = net.minecraft.util.Mth.clampedMap((double) minDist, 0.0D, 20.0D, -0.2D, 0.0D);
        if (absVt + edgeRoundoff < (double) 0.4F) {
            return defaultBlock;
        }
        CacheAllInCellAccess vrCell = this.chunking$veinRidgedCell;
        double vr;
        if (vrCell != null) {
            vr = vrCell.chunking$cellValues()[cellIdx];
        } else {
            if (!coordsSet) {
                this.inCellX = inX;
                this.inCellY = inY;
                this.inCellZ = inZ;
                coordsSet = true;
            }
            vr = this.chunking$vrFn.compute((DensityFunction.FunctionContext) this);
        }
        if (vr >= 0.0D) {
            return defaultBlock;
        }
        net.minecraft.util.RandomSource rng = this.chunking$oreRandom.at(bx, by, bz);
        if (rng.nextFloat() > 0.7F) {
            return defaultBlock;
        }
        double richness = net.minecraft.util.Mth.clampedMap(
                absVt, (double) 0.4F, (double) 0.6F, (double) 0.1F, (double) 0.3F);
        if ((double) rng.nextFloat() < richness) {
            if (!coordsSet) {
                this.inCellX = inX;
                this.inCellY = inY;
                this.inCellZ = inZ;
            }
            DensityFunction vgFn = (this.chunking$veinGapCell != null)
                    ? this.chunking$veinGapCell.chunking$cellNoiseFiller()
                    : this.chunking$vgFn;
            double vg = vgFn.compute((DensityFunction.FunctionContext) this);
            if (vg > (double) -0.3F) {
                return com.chunking.engine.world.FastOreVeins.selectOreBlock(
                        vt > 0.0D, rng.nextFloat() < 0.02F);
            }
        }
        return com.chunking.engine.world.FastOreVeins.selectFillerBlock(vt > 0.0D);
    }

    @Override
    public BlockState chunking$getInterpolatedStateFast() {
        return this.getInterpolatedState();
    }

    @Override
    public double[] chunking$getFullDensityCellValues() {
        CacheAllInCellAccess cell = this.chunking$fullNoiseCell;
        return cell != null ? cell.chunking$cellValues() : null;
    }

    @Override
    public double[] chunking$getVeinToggleValues() {
        if (this.chunking$activeVtValues != null) {
            return this.chunking$activeVtValues;
        }
        CacheAllInCellAccess cell = this.chunking$veinToggleCell;
        return cell != null ? cell.chunking$cellValues() : null;
    }

    @Override
    public double[] chunking$getVeinRidgedValues() {
        CacheAllInCellAccess cell = this.chunking$veinRidgedCell;
        return cell != null ? cell.chunking$cellValues() : null;
    }

    @Override
    public double[] chunking$getVeinGapValues() {
        CacheAllInCellAccess cell = this.chunking$veinGapCell;
        return cell != null ? cell.chunking$cellValues() : null;
    }

    @Override
    public net.minecraft.world.level.levelgen.PositionalRandomFactory chunking$getOreRandom() {
        return this.chunking$oreRandom;
    }

    @Override
    public boolean chunking$isCellRangeGlobalFluidAir(int x0, int x1, int minY, int maxY, int z0, int z1) {
        if (SharedConstants.DEBUG_AQUIFERS || SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) {
            return false;
        }
        if (!(this.aquifer instanceof AquiferAccess aq)) {
            return false;
        }
        if (minY <= aq.chunking$skipSamplingAboveY()) {
            return false;
        }
        return aq.chunking$isGlobalFluidAirRange(x0, minY, maxY, z0)
                && aq.chunking$isGlobalFluidAirRange(x1, minY, maxY, z1);
    }

    @Inject(
            method = "fillSlice",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;cellStartBlockX:I",
                    opcode = org.objectweb.asm.Opcodes.PUTFIELD
            ),
            require = 0
    )
    private void chunking$markVanillaFillSliceReached(boolean isSlice0, int cellX, CallbackInfo ci) {
        this.chunking$vanillaFillSlice = true;
    }

    @Inject(method = "initializeForFirstCellX", at = @At("HEAD"))
    private void chunking$ensurePooledSlicesBeforeStart(CallbackInfo ci) {
        List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
        boolean customTarget = com.chunking.engine.world.ModCompat.isCustomNoiseChunkTarget(this);
        if (!customTarget && ModConfig.SIMD_NOISE && (this.blender == null || this.blender.isEmpty())) {
            com.chunking.engine.simd.JitSupport.markUnusedInterpolators(
                    this.cellCountXZ > 1 ? this.chunking$initRouter : null, interps, this.aquifer,
                    this.preliminarySurfaceLevel, this.fullNoiseDensity,
                    this.chunking$vtFn, this.chunking$vrFn, this.chunking$vgFn);
        }
        for (int i = 0, n = interps.size(); i < n; i++) {
            NoiseInterpolatorAccess acc = (NoiseInterpolatorAccess) interps.get(i);
            acc.chunking$swapSliceMasks();
            if (customTarget
                    || (!acc.chunking$isUnusedInNoiseFill()
                    && !(acc.chunking$isDeferOreExtras() && !SharedConstants.DEBUG_ORE_VEINS && !SharedConstants.DEBUG_AQUIFERS))) {
                acc.chunking$ensureSlices(this.cellCountY, this.cellCountXZ);
            }
        }
    }

    @Inject(method = "swapSlices", at = @At("HEAD"))
    private void chunking$swapDeferredOreSliceMasks(CallbackInfo ci) {
        NoiseInterpolatorAccess[] oreArr = this.chunking$oreSelectArr;
        if (oreArr != null) {
            for (int i = 0; i < oreArr.length; i++) {
                oreArr[i].chunking$swapSliceMasks();
            }
            return;
        }
        List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
        for (int i = 0, n = interps.size(); i < n; i++) {
            ((NoiseInterpolatorAccess) interps.get(i)).chunking$swapSliceMasks();
        }
    }

    @Inject(method = "stopInterpolation", at = @At("RETURN"))
    private void chunking$recycleInterpolatorSlices(CallbackInfo ci) {
        if (!ModConfig.SIMD_NOISE) {
            return;
        }
        List<NoiseChunk.NoiseInterpolator> interps = this.interpolators;
        for (int i = 0, n = interps.size(); i < n; i++) {
            ((NoiseInterpolatorAccess) interps.get(i)).chunking$recycleSlices();
        }
    }

    @Inject(method = "updateForY", at = @At("HEAD"), cancellable = true)
    private void chunking$fastUpdateForY(int blockY, double ty, CallbackInfo ci) {
        if (ModConfig.SIMD_NOISE) {
            this.inCellY = blockY - this.cellStartBlockY;
            ci.cancel();
        }
    }

    @Inject(method = "updateForX", at = @At("HEAD"), cancellable = true)
    private void chunking$fastUpdateForX(int blockX, double tx, CallbackInfo ci) {
        if (ModConfig.SIMD_NOISE) {
            this.inCellX = blockX - this.cellStartBlockX;
            ci.cancel();
        }
    }

    @Inject(method = "updateForZ", at = @At("HEAD"), cancellable = true)
    private void chunking$fastUpdateForZ(int blockZ, double tz, CallbackInfo ci) {
        if (ModConfig.SIMD_NOISE) {
            this.inCellZ = blockZ - this.cellStartBlockZ;
            this.interpolationCounter++;
            ci.cancel();
        }
    }
}
