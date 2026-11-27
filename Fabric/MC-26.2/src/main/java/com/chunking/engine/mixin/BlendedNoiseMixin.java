package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.BlendedNoiseAccess;
import com.chunking.engine.access.ImprovedNoiseAccess;
import com.chunking.engine.simd.NativeAvx2;
import com.chunking.engine.simd.NoiseMath;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.foreign.MemorySegment;

/**
 * Eliminates virtual octave lookups, null-octave checks, {@code CallbackInfoReturnable<Double>} boxing,
 * redundant volatile barriers, and repeated {@code PerlinNoise.wrap} calls in {@link BlendedNoise#compute}
 * with exact IEEE-754 parity.
 */
@Mixin(BlendedNoise.class)
public abstract class BlendedNoiseMixin implements BlendedNoiseAccess {
    @Unique
    private static final boolean CHUNKING$NATIVE_AVX2_ENABLED = !Boolean.getBoolean("chunking.disable_native_avx2");

    @Shadow @Final private PerlinNoise minLimitNoise;
    @Shadow @Final private PerlinNoise maxLimitNoise;
    @Shadow @Final private PerlinNoise mainNoise;
    @Shadow @Final private double xzMultiplier;
    @Shadow @Final private double yMultiplier;
    @Shadow @Final private double xzFactor;
    @Shadow @Final private double yFactor;
    @Shadow @Final private double smearScaleMultiplier;

    // Immutable after <init> (initialized in <init>)
    @Unique private volatile ImprovedNoise[] chunking$mainOcts;
    @Unique private double[] chunking$mainScales;
    @Unique private double[] chunking$mainInvScales;
    @Unique private byte[][] chunking$mainPerms;
    @Unique private double[] chunking$mainXo;
    @Unique private double[] chunking$mainYo;
    @Unique private double[] chunking$mainZo;
    @Unique private double[] chunking$mainFyFactorScales;
    @Unique private double[] chunking$mainSmearScales;

    @Unique private ImprovedNoise[] chunking$minOcts;
    @Unique private ImprovedNoise[] chunking$maxOcts;
    @Unique private double[] chunking$limitScales;
    @Unique private double[] chunking$limitInvScales;

    @Unique private byte[][] chunking$minPerms;
    @Unique private double[] chunking$minXo;
    @Unique private double[] chunking$minYo;
    @Unique private double[] chunking$minZo;
    @Unique private double[] chunking$minScales;
    @Unique private double[] chunking$minInvScales;
    @Unique private double[] chunking$minYMultScales;
    @Unique private double[] chunking$minSmearScales;

    @Unique private byte[][] chunking$maxPerms;
    @Unique private double[] chunking$maxXo;
    @Unique private double[] chunking$maxYo;
    @Unique private double[] chunking$maxZo;
    @Unique private double[] chunking$maxScales;
    @Unique private double[] chunking$maxInvScales;
    @Unique private double[] chunking$maxYMultScales;
    @Unique private double[] chunking$maxSmearScales;

    @Unique private MemorySegment chunking$nativeOcts;

    @Unique private double chunking$smearY;
    @Unique private double chunking$smearScaledY;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$onInit(CallbackInfo ci) {
        chunking$initTables();
    }

    @Unique
    private synchronized void chunking$initTables() {
        if (this.chunking$mainOcts != null) {
            return;
        }
        double fyFactor = this.yMultiplier / this.yFactor;
        double smearY = this.yMultiplier * this.smearScaleMultiplier;
        double smearScaledY = smearY / this.yFactor;

        int mainCount = 0;
        for (int i = 0; i < 8; i++) {
            if (this.mainNoise.getOctaveNoise(i) != null) {
                mainCount++;
            }
        }
        ImprovedNoise[] mOcts = new ImprovedNoise[mainCount];
        double[] mScales = new double[mainCount];
        double[] mInv = new double[mainCount];
        byte[][] mPerms = new byte[mainCount][];
        double[] mXo = new double[mainCount];
        double[] mYo = new double[mainCount];
        double[] mZo = new double[mainCount];
        double[] mFyScales = new double[mainCount];
        double[] mSmearScales = new double[mainCount];
        double scale = 1.0D;
        int mIdx = 0;
        for (int i = 0; i < 8; i++) {
            ImprovedNoise oct = this.mainNoise.getOctaveNoise(i);
            if (oct != null) {
                ImprovedNoiseAccess acc = (ImprovedNoiseAccess) (Object) oct;
                mOcts[mIdx] = oct;
                mScales[mIdx] = scale;
                mInv[mIdx] = 1.0D / scale;
                mPerms[mIdx] = acc.chunking$perm();
                mXo[mIdx] = acc.chunking$xo();
                mYo[mIdx] = acc.chunking$yo();
                mZo[mIdx] = acc.chunking$zo();
                mFyScales[mIdx] = fyFactor * scale;
                mSmearScales[mIdx] = smearScaledY * scale;
                mIdx++;
            }
            scale /= 2.0D;
        }

        int limitCount = 0;
        int minCount = 0;
        int maxCount = 0;
        for (int i = 0; i < 16; i++) {
            ImprovedNoise minO = this.minLimitNoise.getOctaveNoise(i);
            ImprovedNoise maxO = this.maxLimitNoise.getOctaveNoise(i);
            if (minO != null || maxO != null) {
                limitCount++;
            }
            if (minO != null) {
                minCount++;
            }
            if (maxO != null) {
                maxCount++;
            }
        }
        ImprovedNoise[] minArr = new ImprovedNoise[limitCount];
        ImprovedNoise[] maxArr = new ImprovedNoise[limitCount];
        double[] lScales = new double[limitCount];
        double[] lInv = new double[limitCount];

        byte[][] minPerms = new byte[minCount][];
        double[] minXo = new double[minCount];
        double[] minYo = new double[minCount];
        double[] minZo = new double[minCount];
        double[] minScales = new double[minCount];
        double[] minInv = new double[minCount];
        double[] minYMult = new double[minCount];
        double[] minSmear = new double[minCount];

        byte[][] maxPerms = new byte[maxCount][];
        double[] maxXo = new double[maxCount];
        double[] maxYo = new double[maxCount];
        double[] maxZo = new double[maxCount];
        double[] maxScales = new double[maxCount];
        double[] maxInv = new double[maxCount];
        double[] maxYMult = new double[maxCount];
        double[] maxSmear = new double[maxCount];

        scale = 1.0D;
        int lIdx = 0;
        int mnIdx = 0;
        int mxIdx = 0;
        for (int i = 0; i < 16; i++) {
            ImprovedNoise minO = this.minLimitNoise.getOctaveNoise(i);
            ImprovedNoise maxO = this.maxLimitNoise.getOctaveNoise(i);
            double inv = 1.0D / scale;
            if (minO != null || maxO != null) {
                minArr[lIdx] = minO;
                maxArr[lIdx] = maxO;
                lScales[lIdx] = scale;
                lInv[lIdx] = inv;
                lIdx++;
            }
            if (minO != null) {
                ImprovedNoiseAccess acc = (ImprovedNoiseAccess) (Object) minO;
                minPerms[mnIdx] = acc.chunking$perm();
                minXo[mnIdx] = acc.chunking$xo();
                minYo[mnIdx] = acc.chunking$yo();
                minZo[mnIdx] = acc.chunking$zo();
                minScales[mnIdx] = scale;
                minInv[mnIdx] = inv;
                minYMult[mnIdx] = this.yMultiplier * scale;
                minSmear[mnIdx] = smearY * scale;
                mnIdx++;
            }
            if (maxO != null) {
                ImprovedNoiseAccess acc = (ImprovedNoiseAccess) (Object) maxO;
                maxPerms[mxIdx] = acc.chunking$perm();
                maxXo[mxIdx] = acc.chunking$xo();
                maxYo[mxIdx] = acc.chunking$yo();
                maxZo[mxIdx] = acc.chunking$zo();
                maxScales[mxIdx] = scale;
                maxInv[mxIdx] = inv;
                maxYMult[mxIdx] = this.yMultiplier * scale;
                maxSmear[mxIdx] = smearY * scale;
                mxIdx++;
            }
            scale /= 2.0D;
        }

        this.chunking$smearY = smearY;
        this.chunking$smearScaledY = smearScaledY;
        this.chunking$mainScales = mScales;
        this.chunking$mainInvScales = mInv;
        this.chunking$mainPerms = mPerms;
        this.chunking$mainXo = mXo;
        this.chunking$mainYo = mYo;
        this.chunking$mainZo = mZo;
        this.chunking$mainFyFactorScales = mFyScales;
        this.chunking$mainSmearScales = mSmearScales;
        this.chunking$minOcts = minArr;
        this.chunking$maxOcts = maxArr;
        this.chunking$limitScales = lScales;
        this.chunking$limitInvScales = lInv;
        this.chunking$minPerms = minPerms;
        this.chunking$minXo = minXo;
        this.chunking$minYo = minYo;
        this.chunking$minZo = minZo;
        this.chunking$minScales = minScales;
        this.chunking$minInvScales = minInv;
        this.chunking$minYMultScales = minYMult;
        this.chunking$minSmearScales = minSmear;
        this.chunking$maxPerms = maxPerms;
        this.chunking$maxXo = maxXo;
        this.chunking$maxYo = maxYo;
        this.chunking$maxZo = maxZo;
        this.chunking$maxScales = maxScales;
        this.chunking$maxInvScales = maxInv;
        this.chunking$maxYMultScales = maxYMult;
        this.chunking$maxSmearScales = maxSmear;
        this.chunking$nativeOcts = (CHUNKING$NATIVE_AVX2_ENABLED && NativeAvx2.hasBlendedKernel())
                ? NativeAvx2.packBlendedOctaves(
                        mPerms, mXo, mYo, mZo, mScales, mInv, mFyScales, mSmearScales,
                        minPerms, minXo, minYo, minZo, minScales, minInv, minYMult, minSmear,
                        maxPerms, maxXo, maxYo, maxZo, maxScales, maxInv, maxYMult, maxSmear)
                : null;
        // Publish chunking$mainOcts last
        this.chunking$mainOcts = mOcts;
    }

    @Unique
    private static double chunking$wrap(double v) {
        return (v > -1.6777215E7D && v < 1.6777215E7D) ? v : PerlinNoise.wrap(v);
    }

    /**
     * @author Chunking
     * @reason Eliminate CallbackInfoReturnable&lt;Double&gt; heap boxing on BlendedNoise.compute.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        if (!ModConfig.SIMD_NOISE) {
            double d = ctx.blockX() * this.xzMultiplier;
            double e = ctx.blockY() * this.yMultiplier;
            double f = ctx.blockZ() * this.xzMultiplier;
            double g = d / this.xzFactor;
            double h = e / this.yFactor;
            double i = f / this.xzFactor;
            double j = this.yMultiplier * this.smearScaleMultiplier;
            double k = j / this.yFactor;
            double l = 0.0D;
            double m = 0.0D;
            double n = 0.0D;
            double p = 1.0D;
            for (int q = 0; q < 8; q++) {
                ImprovedNoise oct = this.mainNoise.getOctaveNoise(q);
                if (oct != null) {
                    n += oct.noise(PerlinNoise.wrap(g * p), PerlinNoise.wrap(h * p), PerlinNoise.wrap(i * p), k * p, h * p) / p;
                }
                p /= 2.0D;
            }
            double r = (n / 10.0D + 1.0D) / 2.0D;
            boolean bl2 = r >= 1.0D;
            boolean bl3 = r <= 0.0D;
            p = 1.0D;
            for (int s = 0; s < 16; s++) {
                double t = PerlinNoise.wrap(d * p);
                double u = PerlinNoise.wrap(e * p);
                double v = PerlinNoise.wrap(f * p);
                double w = j * p;
                if (!bl2) {
                    ImprovedNoise minOct = this.minLimitNoise.getOctaveNoise(s);
                    if (minOct != null) {
                        l += minOct.noise(t, u, v, w, e * p) / p;
                    }
                }
                if (!bl3) {
                    ImprovedNoise maxOct = this.maxLimitNoise.getOctaveNoise(s);
                    if (maxOct != null) {
                        m += maxOct.noise(t, u, v, w, e * p) / p;
                    }
                }
                p /= 2.0D;
            }
            return Mth.clampedLerp(r, l / 512.0D, m / 512.0D) / 128.0D;
        }
        return chunking$computeFast(ctx.blockX(), ctx.blockY(), ctx.blockZ());
    }

    @Override
    public double chunking$computeFast(int bx, int by, int bz) {
        ImprovedNoise[] mOcts = this.chunking$mainOcts;
        if (mOcts == null) {
            chunking$initTables();
            mOcts = this.chunking$mainOcts;
        }

        double dx = bx * this.xzMultiplier;
        double dy = by * this.yMultiplier;
        double dz = bz * this.xzMultiplier;

        double fx = dx / this.xzFactor;
        double fy = dy / this.yFactor;
        double fz = dz / this.xzFactor;

        double smearY = this.chunking$smearY;
        double smearScaledY = this.chunking$smearScaledY;

        double mainSum = 0.0D;
        double[] mScales = this.chunking$mainScales;
        double[] mInv = this.chunking$mainInvScales;
        for (int i = 0; i < mOcts.length; i++) {
            double s = mScales[i];
            double scaledFy = fy * s;
            mainSum += mOcts[i].noise(
                    chunking$wrap(fx * s),
                    chunking$wrap(scaledFy),
                    chunking$wrap(fz * s),
                    smearScaledY * s,
                    scaledFy
            ) * mInv[i];
        }

        double blend = (mainSum / 10.0D + 1.0D) / 2.0D;
        boolean skipMin = blend >= 1.0D;
        boolean skipMax = blend <= 0.0D;

        double minSum = 0.0D;
        double maxSum = 0.0D;
        if (!skipMin || !skipMax) {
            ImprovedNoise[] minArr = this.chunking$minOcts;
            ImprovedNoise[] maxArr = this.chunking$maxOcts;
            double[] lScales = this.chunking$limitScales;
            double[] lInv = this.chunking$limitInvScales;

            for (int i = 0; i < lScales.length; i++) {
                double s = lScales[i];
                double inv = lInv[i];
                double wx = chunking$wrap(dx * s);
                double scaledDy = dy * s;
                double wy = chunking$wrap(scaledDy);
                double wz = chunking$wrap(dz * s);
                double ws = smearY * s;

                if (!skipMin) {
                    ImprovedNoise minO = minArr[i];
                    if (minO != null) {
                        minSum += minO.noise(wx, wy, wz, ws, scaledDy) * inv;
                    }
                }
                if (!skipMax) {
                    ImprovedNoise maxO = maxArr[i];
                    if (maxO != null) {
                        maxSum += maxO.noise(wx, wy, wz, ws, scaledDy) * inv;
                    }
                }
            }
        }

        return Mth.clampedLerp(blend, minSum / 512.0D, maxSum / 512.0D) / 128.0D;
    }

    @Unique
    private static final class BlendedColBuf {
        double[] main = new double[64];
        double[] min = new double[64];
        double[] max = new double[64];

        void ensure(int count) {
            if (this.main.length < count) {
                this.main = new double[count];
                this.min = new double[count];
                this.max = new double[count];
            }
        }
    }

    @Unique
    private static final ThreadLocal<BlendedColBuf> CHUNKING$COL_BUF_TL =
            ThreadLocal.withInitial(BlendedColBuf::new);

    @Override
    public void chunking$evalColumnFast(int bx, int[] by, int bz, double[] out, int count) {
        chunking$evalColumnRange(bx, by, 0, bz, out, count);
    }

    @Override
    public void chunking$evalColumnRange(int bx, int[] by, int startIdx, int bz, double[] out, int totalCount) {
        int sIdx = Math.max(0, startIdx);
        if (sIdx >= totalCount) {
            return;
        }
        if (this.chunking$mainOcts == null) {
            chunking$initTables();
        }

        double dx = bx * this.xzMultiplier;
        double dz = bz * this.xzMultiplier;
        double fx = dx / this.xzFactor;
        double fz = dz / this.xzFactor;

        MemorySegment nativeOcts = this.chunking$nativeOcts;
        if (CHUNKING$NATIVE_AVX2_ENABLED && nativeOcts != null && (totalCount - sIdx) >= 4
                && NativeAvx2.hasBlendedKernel()) {
            NativeAvx2.evalBlendedColumn(
                    nativeOcts,
                    this.chunking$mainPerms.length,
                    this.chunking$minPerms.length,
                    this.chunking$maxPerms.length,
                    dx, dz, fx, fz,
                    by, sIdx, out, totalCount
            );
            return;
        }

        BlendedColBuf buf = CHUNKING$COL_BUF_TL.get();
        buf.ensure(totalCount);
        double[] mainArr = buf.main;
        double[] minArr = buf.min;
        double[] maxArr = buf.max;
        java.util.Arrays.fill(mainArr, sIdx, totalCount, 0.0D);

        byte[][] mPerms = this.chunking$mainPerms;
        double[] mXo = this.chunking$mainXo;
        double[] mYo = this.chunking$mainYo;
        double[] mZo = this.chunking$mainZo;
        double[] mScales = this.chunking$mainScales;
        double[] mInv = this.chunking$mainInvScales;
        double[] mFyScales = this.chunking$mainFyFactorScales;
        double[] mSmearScales = this.chunking$mainSmearScales;
        for (int i = 0; i < mPerms.length; i++) {
            double s = mScales[i];
            NoiseMath.evalOctaveColumnWithSmearRange(
                    mPerms[i], mXo[i], mYo[i], mZo[i],
                    fx * s, by, sIdx, mFyScales[i], fz * s, mSmearScales[i], mInv[i],
                    mainArr, totalCount, 0, null);
        }

        int minFirst = totalCount;
        int minLast = sIdx;
        int maxFirst = totalCount;
        int maxLast = sIdx;
        for (int i = sIdx; i < totalCount; i++) {
            double b = (mainArr[i] / 10.0D + 1.0D) / 2.0D;
            mainArr[i] = b;
            if (b < 1.0D) {
                if (i < minFirst) {
                    minFirst = i;
                }
                minLast = i + 1;
            }
            if (b > 0.0D) {
                if (i < maxFirst) {
                    maxFirst = i;
                }
                maxLast = i + 1;
            }
        }

        boolean anyMin = minFirst < minLast;
        boolean anyMax = maxFirst < maxLast;
        if (anyMin) {
            java.util.Arrays.fill(minArr, minFirst, minLast, 0.0D);
            byte[][] minPerms = this.chunking$minPerms;
            if (minPerms.length > 0) {
                double[] minXo = this.chunking$minXo;
                double[] minYo = this.chunking$minYo;
                double[] minZo = this.chunking$minZo;
                double[] minScales = this.chunking$minScales;
                double[] minInv = this.chunking$minInvScales;
                double[] minYMult = this.chunking$minYMultScales;
                double[] minSmear = this.chunking$minSmearScales;
                int maskMode = anyMax ? 1 : 0;
                for (int i = 0; i < minPerms.length; i++) {
                    double s = minScales[i];
                    NoiseMath.evalOctaveColumnWithSmearRange(
                            minPerms[i], minXo[i], minYo[i], minZo[i],
                            dx * s, by, minFirst, minYMult[i], dz * s, minSmear[i], minInv[i],
                            minArr, minLast, maskMode, mainArr);
                }
            }
        }
        if (anyMax) {
            java.util.Arrays.fill(maxArr, maxFirst, maxLast, 0.0D);
            byte[][] maxPerms = this.chunking$maxPerms;
            if (maxPerms.length > 0) {
                double[] maxXo = this.chunking$maxXo;
                double[] maxYo = this.chunking$maxYo;
                double[] maxZo = this.chunking$maxZo;
                double[] maxScales = this.chunking$maxScales;
                double[] maxInv = this.chunking$maxInvScales;
                double[] maxYMult = this.chunking$maxYMultScales;
                double[] maxSmear = this.chunking$maxSmearScales;
                int maskMode = anyMin ? 2 : 0;
                for (int i = 0; i < maxPerms.length; i++) {
                    double s = maxScales[i];
                    NoiseMath.evalOctaveColumnWithSmearRange(
                            maxPerms[i], maxXo[i], maxYo[i], maxZo[i],
                            dx * s, by, maxFirst, maxYMult[i], dz * s, maxSmear[i], maxInv[i],
                            maxArr, maxLast, maskMode, mainArr);
                }
            }
        }

        if (!anyMin && !anyMax) {
            for (int i = sIdx; i < totalCount; i++) {
                out[i] = Double.NaN;
            }
        } else if (!anyMin) {
            for (int i = sIdx; i < totalCount; i++) {
                out[i] = (maxArr[i] / 512.0D) / 128.0D;
            }
        } else if (!anyMax) {
            for (int i = sIdx; i < totalCount; i++) {
                out[i] = (minArr[i] / 512.0D) / 128.0D;
            }
        } else {
            for (int i = sIdx; i < totalCount; i++) {
                out[i] = Mth.clampedLerp(mainArr[i], minArr[i] / 512.0D, maxArr[i] / 512.0D) / 128.0D;
            }
        }
    }
}
