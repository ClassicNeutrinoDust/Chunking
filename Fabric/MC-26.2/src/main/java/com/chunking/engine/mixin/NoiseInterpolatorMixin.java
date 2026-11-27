package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.NoiseChunkAccessor;
import com.chunking.engine.simd.JitSupport;
import com.chunking.engine.access.NoiseInterpolatorAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces per-voxel {@code Mth.lerp3} callbacks in {@link NoiseChunk.NoiseInterpolator#fillArray}
 * with separable Y -> X -> Z trilinear interpolation during cell filling, shares chunk-border
 * vertical slices across adjacent chunks, and exposes cell corners for compiled JIT kernels.
 */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin implements NoiseInterpolatorAccess {
    @Shadow
    @Final
    NoiseChunk this$0;

    @Shadow
    @Final
    private DensityFunction noiseFiller;

    @Shadow
    private double noise000;
    @Shadow
    private double noise001;
    @Shadow
    private double noise100;
    @Shadow
    private double noise101;
    @Shadow
    private double noise010;
    @Shadow
    private double noise011;
    @Shadow
    private double noise110;
    @Shadow
    private double noise111;

    @Shadow
    private double[][] slice0;

    @Shadow
    private double[][] slice1;

    @Shadow
    private double value;

    @Shadow
    private void selectCellYZ(int cellY, int cellZ) {}

    @Shadow
    private double[][] allocateSlice(int cellCountY, int cellCountXZ) {
        throw new AssertionError();
    }

    @org.spongepowered.asm.mixin.Unique
    private static final double[][] CHUNKING$DUMMY_SLICE = chunking$createDummySlice();

    @org.spongepowered.asm.mixin.Unique
    private static double[][] chunking$createDummySlice() {
        double[] sharedCol = new double[1025];
        double[][] slice = new double[17][];
        for (int i = 0; i < 17; i++) {
            slice[i] = sharedCol;
        }
        return slice;
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$allowSliceAlloc;

    @org.spongepowered.asm.mixin.Unique
    private int chunking$savedCellCountY;

    @org.spongepowered.asm.mixin.Unique
    private int chunking$savedCellCountXZ;

    @org.spongepowered.asm.mixin.injection.Inject(method = "allocateSlice", at = @At("HEAD"), cancellable = true)
    private void chunking$borrowPooledSlice(int cellCountY, int cellCountXZ, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<double[][]> cir) {
        this.chunking$savedCellCountY = cellCountY;
        this.chunking$savedCellCountXZ = cellCountXZ;
        if (!ModConfig.SIMD_NOISE) {
            return;
        }
        if (!this.chunking$allowSliceAlloc
                && cellCountXZ + 1 <= CHUNKING$DUMMY_SLICE.length
                && cellCountY + 1 <= CHUNKING$DUMMY_SLICE[0].length) {
            cir.setReturnValue(CHUNKING$DUMMY_SLICE);
            return;
        }
        double[][] pooled = JitSupport.borrowInterpolatorSlice(cellCountXZ + 1, cellCountY + 1);
        if (pooled != null) {
            cir.setReturnValue(pooled);
        }
    }

    @Override
    public void chunking$recycleSlices() {
        if (this.slice0 != null && this.slice0 != CHUNKING$DUMMY_SLICE) {
            JitSupport.recycleInterpolatorSlice(this.slice0);
        }
        this.slice0 = CHUNKING$DUMMY_SLICE;
        if (this.slice1 != null && this.slice1 != CHUNKING$DUMMY_SLICE) {
            JitSupport.recycleInterpolatorSlice(this.slice1);
        }
        this.slice1 = CHUNKING$DUMMY_SLICE;
    }

    @Override
    public void chunking$ensureSlices(int cellCountY, int cellCountXZ) {
        this.chunking$allowSliceAlloc = true;
        if (this.slice0 == null || this.slice0 == CHUNKING$DUMMY_SLICE) {
            this.slice0 = this.allocateSlice(cellCountY, cellCountXZ);
        }
        if (this.slice1 == null || this.slice1 == CHUNKING$DUMMY_SLICE) {
            this.slice1 = this.allocateSlice(cellCountY, cellCountXZ);
        }
    }

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void chunking$separableTrilinearFill(double[] out, DensityFunction.ContextProvider provider, CallbackInfo ci) {
        if (!ModConfig.SIMD_NOISE || out == null || provider != this.this$0) {
            return;
        }
        NoiseChunkAccessor access = (NoiseChunkAccessor) this.this$0;
        int cw = access.chunking$getCellWidth();
        int ch = access.chunking$getCellHeight();
        if (cw <= 0 || ch <= 0 || out.length != cw * cw * ch) {
            return;
        }

        double n000 = this.noise000;
        double n100 = this.noise100;
        double n010 = this.noise010;
        double n110 = this.noise110;
        double n001 = this.noise001;
        double n101 = this.noise101;
        double n011 = this.noise011;
        double n111 = this.noise111;
        double d00 = n010 - n000;
        double d10 = n110 - n100;
        double d01 = n011 - n001;
        double d11 = n111 - n101;
        double invCh = 1.0D / (double) ch;
        double invCw = 1.0D / (double) cw;
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
                    out[idx++] = z0 + ((double) z * invCw) * dz;
                }
            }
        }
        ci.cancel();
    }

    /**
     * @author Chunking
     * @reason Eliminate CallbackInfoReturnable&lt;Double&gt; heap boxing on NoiseInterpolator.compute.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext context) {
        if (context != this.this$0) {
            return this.noiseFiller.compute(context);
        }
        NoiseChunkAccessor access = (NoiseChunkAccessor) this.this$0;
        if (!access.chunking$isInterpolating()) {
            throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
        }
        boolean filling = access.chunking$isFillingCell();
        if (!ModConfig.SIMD_NOISE && !filling) {
            return this.value;
        }
        if (filling) {
            int cw = access.chunking$getCellWidth();
            int ch = access.chunking$getCellHeight();
            double invCw = (cw == 4) ? 0.25D : (1.0D / (double) cw);
            double invCh = (ch == 8) ? 0.125D : (1.0D / (double) ch);
            return Mth.lerp3(
                    (double) access.chunking$getInCellX() * invCw,
                    (double) access.chunking$getInCellY() * invCh,
                    (double) access.chunking$getInCellZ() * invCw,
                    this.noise000, this.noise100, this.noise010, this.noise110,
                    this.noise001, this.noise101, this.noise011, this.noise111
            );
        }
        double ty = (double) access.chunking$getInCellY() / access.chunking$getCellHeight();
        double tx = (double) access.chunking$getInCellX() / access.chunking$getCellWidth();
        double tz = (double) access.chunking$getInCellZ() / access.chunking$getCellWidth();
        double xz00 = Mth.lerp(ty, this.noise000, this.noise010);
        double xz10 = Mth.lerp(ty, this.noise100, this.noise110);
        double xz01 = Mth.lerp(ty, this.noise001, this.noise011);
        double xz11 = Mth.lerp(ty, this.noise101, this.noise111);
        double z0 = Mth.lerp(tx, xz00, xz10);
        double z1 = Mth.lerp(tx, xz01, xz11);
        return Mth.lerp(tz, z0, z1);
    }

    @Override
    public double chunking$getNoise000() {
        return this.noise000;
    }

    @Override
    public double chunking$getNoise001() {
        return this.noise001;
    }

    @Override
    public double chunking$getNoise100() {
        return this.noise100;
    }

    @Override
    public double chunking$getNoise101() {
        return this.noise101;
    }

    @Override
    public double chunking$getNoise010() {
        return this.noise010;
    }

    @Override
    public double chunking$getNoise011() {
        return this.noise011;
    }

    @Override
    public double chunking$getNoise110() {
        return this.noise110;
    }

    @Override
    public double chunking$getNoise111() {
        return this.noise111;
    }

    @Override
    public double chunking$evalUnaligned(int bx, int by, int bz) {
        return this.noiseFiller.compute(JitSupport.borrowContext(bx, by, bz));
    }

    @org.spongepowered.asm.mixin.Shadow
    protected abstract void updateForY(double ty);

    @org.spongepowered.asm.mixin.Shadow
    protected abstract void updateForX(double tx);

    @org.spongepowered.asm.mixin.Shadow
    protected abstract void updateForZ(double tz);

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$oreVeinOnly;

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$unusedInNoiseFill;

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$deferOreExtras;

    @org.spongepowered.asm.mixin.Unique
    private int chunking$filledMask0;

    @org.spongepowered.asm.mixin.Unique
    private int chunking$filledMask1;

    @org.spongepowered.asm.mixin.Unique
    private com.chunking.engine.simd.NoiseJit.BoundKernel chunking$sliceKernel;

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$sliceKernelResolved;

    @org.spongepowered.asm.mixin.Unique
    private int chunking$interpOrdinal;

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$interpOrdinalResolved;

    @Override
    public void chunking$selectCellYZ(int cellY, int cellZ) {
        this.selectCellYZ(cellY, cellZ);
    }

    @Override
    public void chunking$setOreVeinOnly(boolean oreVeinOnly) {
        this.chunking$oreVeinOnly = oreVeinOnly;
    }

    @Override
    public boolean chunking$isOreVeinOnly() {
        return this.chunking$oreVeinOnly;
    }

    @Override
    public void chunking$setUnusedInNoiseFill(boolean unused) {
        this.chunking$unusedInNoiseFill = unused;
    }

    @Override
    public boolean chunking$isUnusedInNoiseFill() {
        return this.chunking$unusedInNoiseFill;
    }

    @Override
    public void chunking$setDeferOreExtras(boolean defer) {
        this.chunking$deferOreExtras = defer;
    }

    @Override
    public boolean chunking$isDeferOreExtras() {
        return this.chunking$deferOreExtras;
    }

    @Override
    public void chunking$swapSliceMasks() {
        this.chunking$filledMask0 = this.chunking$filledMask1;
        this.chunking$filledMask1 = 0;
    }

    @org.spongepowered.asm.mixin.Unique
    private int chunking$getOrResolveOrdinal(com.chunking.engine.access.NoiseSliceEval eval) {
        if (!this.chunking$interpOrdinalResolved) {
            this.chunking$interpOrdinal = eval.chunking$resolveInterpolatorOrdinal((NoiseChunk.NoiseInterpolator) (Object) this);
            this.chunking$interpOrdinalResolved = true;
        }
        return this.chunking$interpOrdinal;
    }

    @Override
    public void chunking$ensureCellCornersFilled(
            net.minecraft.world.level.levelgen.NoiseChunk nc,
            int x0, int x1, int z0, int z1, int cellZ
    ) {
        int b0 = 1 << cellZ;
        int b1 = 1 << (cellZ + 1);
        int need = b0 | b1;
        int m0 = this.chunking$filledMask0;
        int m1 = this.chunking$filledMask1;
        if ((m0 & need) == need && (m1 & need) == need) {
            return;
        }
        com.chunking.engine.access.NoiseSliceEval eval =
                (com.chunking.engine.access.NoiseSliceEval) nc;
        com.chunking.engine.simd.NoiseJit.BoundKernel bound = this.chunking$sliceKernel;
        if (bound == null && !this.chunking$sliceKernelResolved) {
            bound = eval.chunking$resolveSliceKernel((NoiseChunk.NoiseInterpolator) (Object) this, this.noiseFiller);
            this.chunking$sliceKernel = bound;
            this.chunking$sliceKernelResolved = true;
        }
        if (this.slice0 == null || this.slice0 == CHUNKING$DUMMY_SLICE
                || this.slice1 == null || this.slice1 == CHUNKING$DUMMY_SLICE) {
            chunking$ensureSlices(this.chunking$savedCellCountY, this.chunking$savedCellCountXZ);
        }
        int ord = chunking$getOrResolveOrdinal(eval);
        double[][] s0 = this.slice0;
        double[][] s1 = this.slice1;
        int maxCount = eval.chunking$getOreVeinSliceCount(s0[cellZ].length);
        if (bound != null) {
            if ((m0 & b0) == 0) {
                if (!eval.chunking$evalVerticalSliceAtCached(s0[cellZ], bound, x0, z0, maxCount, ord)) {
                    chunking$fillCornerScalar(nc, s0[cellZ], x0, z0, maxCount);
                }
                m0 |= b0;
            }
            if ((m0 & b1) == 0) {
                if (!eval.chunking$evalVerticalSliceAtCached(s0[cellZ + 1], bound, x0, z1, maxCount, ord)) {
                    chunking$fillCornerScalar(nc, s0[cellZ + 1], x0, z1, maxCount);
                }
                m0 |= b1;
            }
            if ((m1 & b0) == 0) {
                if (!eval.chunking$evalVerticalSliceAtCached(s1[cellZ], bound, x1, z0, maxCount, ord)) {
                    chunking$fillCornerScalar(nc, s1[cellZ], x1, z0, maxCount);
                }
                m1 |= b0;
            }
            if ((m1 & b1) == 0) {
                if (!eval.chunking$evalVerticalSliceAtCached(s1[cellZ + 1], bound, x1, z1, maxCount, ord)) {
                    chunking$fillCornerScalar(nc, s1[cellZ + 1], x1, z1, maxCount);
                }
                m1 |= b1;
            }
        } else {
            NoiseChunkAccessor acc = (NoiseChunkAccessor) nc;
            int minYCell = acc.chunking$getCellNoiseMinY();
            int ch = acc.chunking$getCellHeight();
            JitSupport.MutableContext ctx = JitSupport.borrowContext(0, 0, 0);
            if ((m0 & b0) == 0) {
                double[] dst = s0[cellZ];
                for (int i = 0; i < maxCount; i++) dst[i] = this.noiseFiller.compute(ctx.set(x0, (i + minYCell) * ch, z0));
                m0 |= b0;
            }
            if ((m0 & b1) == 0) {
                double[] dst = s0[cellZ + 1];
                for (int i = 0; i < maxCount; i++) dst[i] = this.noiseFiller.compute(ctx.set(x0, (i + minYCell) * ch, z1));
                m0 |= b1;
            }
            if ((m1 & b0) == 0) {
                double[] dst = s1[cellZ];
                for (int i = 0; i < maxCount; i++) dst[i] = this.noiseFiller.compute(ctx.set(x1, (i + minYCell) * ch, z0));
                m1 |= b0;
            }
            if ((m1 & b1) == 0) {
                double[] dst = s1[cellZ + 1];
                for (int i = 0; i < maxCount; i++) dst[i] = this.noiseFiller.compute(ctx.set(x1, (i + minYCell) * ch, z1));
                m1 |= b1;
            }
        }
        this.chunking$filledMask0 = m0;
        this.chunking$filledMask1 = m1;
    }

    @org.spongepowered.asm.mixin.Unique
    private void chunking$fillCornerScalar(NoiseChunk nc, double[] dst, int x, int z, int maxCount) {
        NoiseChunkAccessor acc = (NoiseChunkAccessor) nc;
        int minYCell = acc.chunking$getCellNoiseMinY();
        int ch = acc.chunking$getCellHeight();
        JitSupport.MutableContext ctx = JitSupport.borrowContext(0, 0, 0);
        int px = ctx.x, py = ctx.y, pz = ctx.z;
        try {
            for (int i = 0; i < maxCount; i++) {
                dst[i] = this.noiseFiller.compute(ctx.set(x, (i + minYCell) * ch, z));
            }
        } finally {
            ctx.set(px, py, pz);
        }
    }

    @Override
    public void chunking$updateForY(double ty) {
        this.updateForY(ty);
    }

    @Override
    public void chunking$updateForX(double tx) {
        this.updateForX(tx);
    }

    @Override
    public void chunking$updateForZ(double tz) {
        this.updateForZ(tz);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "fillArray", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void chunking$fastSliceFill(
            double[] array,
            net.minecraft.world.level.levelgen.DensityFunction.ContextProvider contextProvider,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        if (this.slice0 == null || this.slice0 == CHUNKING$DUMMY_SLICE
                || this.slice1 == null || this.slice1 == CHUNKING$DUMMY_SLICE) {
            chunking$ensureSlices(this.chunking$savedCellCountY, this.chunking$savedCellCountXZ);
        }
        if (array.length > 0 && array == CHUNKING$DUMMY_SLICE[0]) {
            ci.cancel();
            return;
        }
        if (!com.chunking.engine.config.ModConfig.SIMD_NOISE
                || !(contextProvider instanceof com.chunking.engine.access.SliceProviderAccess sliceAcc)) {
            return;
        }
        net.minecraft.world.level.levelgen.NoiseChunk nc = sliceAcc.chunking$getNoiseChunk();
        if (((com.chunking.engine.mixin.accessor.NoiseChunkAccessor) nc).chunking$isFillingCell()) {
            return;
        }
        if (this.chunking$unusedInNoiseFill
                || (this.chunking$deferOreExtras && !net.minecraft.SharedConstants.DEBUG_ORE_VEINS && !net.minecraft.SharedConstants.DEBUG_AQUIFERS)) {
            ci.cancel();
            return;
        }
        com.chunking.engine.access.NoiseSliceEval eval =
                (com.chunking.engine.access.NoiseSliceEval) nc;
        com.chunking.engine.simd.NoiseJit.BoundKernel bound = this.chunking$sliceKernel;
        if (bound == null && !this.chunking$sliceKernelResolved) {
            bound = eval.chunking$resolveSliceKernel((NoiseChunk.NoiseInterpolator) (Object) this, this.noiseFiller);
            this.chunking$sliceKernel = bound;
            this.chunking$sliceKernelResolved = true;
        }
        if (bound == null) {
            return;
        }
        if (this.chunking$oreVeinOnly && !net.minecraft.SharedConstants.DEBUG_ORE_VEINS) {
            eval.chunking$setMaxSliceCount(eval.chunking$getOreVeinSliceCount(array.length));
        }
        int ord = chunking$getOrResolveOrdinal(eval);
        if (eval.chunking$evalVerticalSliceCached(array, bound, ord)) {
            eval.chunking$setMaxSliceCount(0);
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "fillArray", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void chunking$resetSliceCap(
            double[] array,
            net.minecraft.world.level.levelgen.DensityFunction.ContextProvider contextProvider,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        if (this.chunking$oreVeinOnly
                && contextProvider instanceof com.chunking.engine.access.SliceProviderAccess sliceAcc) {
            ((com.chunking.engine.access.NoiseSliceEval) sliceAcc.chunking$getNoiseChunk())
                    .chunking$setMaxSliceCount(0);
        }
    }
}
