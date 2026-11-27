package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.ImprovedNoiseAccess;
import com.chunking.engine.simd.NativeAvx2;
import com.chunking.engine.access.NormalNoiseAccess;
import com.chunking.engine.access.PerlinNoiseAccess;
import com.chunking.engine.simd.NoiseMath;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Fuses {@code first} and {@code second} Perlin octave sets into one flat loop with
 * pre-multiplied {@code amp * valFactor * valueFactor} scales and optional AVX2 batching.
 */
@Mixin(NormalNoise.class)
public abstract class NormalNoiseMixin implements NormalNoiseAccess {
    @Unique
    private static final double CHUNKING$INPUT_FACTOR = 1.0181268882175227D;
    @Unique
    private static final boolean CHUNKING$NATIVE_AVX2_ENABLED = !Boolean.getBoolean("chunking.disable_native_avx2");

    @Shadow
    @Final
    private PerlinNoise first;

    @Shadow
    @Final
    private PerlinNoise second;

    @Shadow
    @Final
    private double valueFactor;

    @Unique
    private volatile ImprovedNoise[] chunking$fusedOctaves;

    @Unique
    private double[] chunking$fusedInputFactors;

    @Unique
    private double[] chunking$fusedScales;

    @Unique
    private volatile java.lang.foreign.MemorySegment chunking$nativeOcts;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$onInit(CallbackInfo ci) {
        chunking$initFused();
    }

    @Unique
    private synchronized void chunking$initFused() {
        if (this.chunking$fusedOctaves != null) {
            return;
        }
        PerlinNoiseAccess p1 = (PerlinNoiseAccess) this.first;
        PerlinNoiseAccess p2 = (PerlinNoiseAccess) this.second;
        ImprovedNoise[] o1 = p1.chunking$activeOctaves();
        double[] f1 = p1.chunking$activeInputFactors();
        double[] s1 = p1.chunking$activeCombinedScales();
        ImprovedNoise[] o2 = p2.chunking$activeOctaves();
        double[] f2 = p2.chunking$activeInputFactors();
        double[] s2 = p2.chunking$activeCombinedScales();

        int n1 = o1.length;
        int n2 = o2.length;
        int total = n1 + n2;
        ImprovedNoise[] allOcts = new ImprovedNoise[total];
        double[] allFactors = new double[total];
        double[] allScales = new double[total];
        double vf = this.valueFactor;

        for (int i = 0; i < n1; i++) {
            allOcts[i] = o1[i];
            allFactors[i] = f1[i];
            allScales[i] = s1[i] * vf;
        }
        for (int i = 0; i < n2; i++) {
            int dst = n1 + i;
            allOcts[dst] = o2[i];
            allFactors[dst] = f2[i] * CHUNKING$INPUT_FACTOR;
            allScales[dst] = s2[i] * vf;
        }
        this.chunking$fusedInputFactors = allFactors;
        this.chunking$fusedScales = allScales;
        this.chunking$nativeOcts = CHUNKING$NATIVE_AVX2_ENABLED
                ? NativeAvx2.packOctaves(allOcts, allFactors, allScales)
                : null;
        this.chunking$fusedOctaves = allOcts;
    }

    @Unique
    private static double chunking$wrap(double v) {
        return (v > -1.6777215E7D && v < 1.6777215E7D) ? v : PerlinNoise.wrap(v);
    }

    /**
     * @author Chunking
     * @reason Single-loop fused dual-Perlin octave sampling with pre-scaled weights.
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        if (!ModConfig.SIMD_NOISE) {
            double d = x * CHUNKING$INPUT_FACTOR;
            double e = y * CHUNKING$INPUT_FACTOR;
            double f = z * CHUNKING$INPUT_FACTOR;
            return (this.first.getValue(x, y, z) + this.second.getValue(d, e, f)) * this.valueFactor;
        }
        return chunking$sampleNormalFast(x, y, z);
    }

    @Override
    public double chunking$sampleNormalFast(double x, double y, double z) {
        ImprovedNoise[] octs = this.chunking$fusedOctaves;
        if (octs == null) {
            chunking$initFused();
            octs = this.chunking$fusedOctaves;
        }
        double[] factors = this.chunking$fusedInputFactors;
        double[] scales = this.chunking$fusedScales;
        double acc = 0.0D;
        for (int i = 0; i < octs.length; i++) {
            double f = factors[i];
            acc += octs[i].noise(
                    chunking$wrap(x * f),
                    chunking$wrap(y * f),
                    chunking$wrap(z * f),
                    0.0D,
                    0.0D
            ) * scales[i];
        }
        return acc;
    }

    @Override
    public void chunking$sampleColumnFast(
            double xFixed,
            int[] by,
            double yScale,
            double zFixed,
            double[] out,
            int count
    ) {
        ImprovedNoise[] octs = this.chunking$fusedOctaves;
        if (octs == null) {
            chunking$initFused();
            octs = this.chunking$fusedOctaves;
        }
        java.lang.foreign.MemorySegment nativeOcts = this.chunking$nativeOcts;
        if (CHUNKING$NATIVE_AVX2_ENABLED && nativeOcts != null && count >= 4 && NativeAvx2.isAvailable()) {
            NativeAvx2.evalNormalColumn(nativeOcts, octs.length, xFixed, by, yScale, zFixed, out, count);
            return;
        }
        double[] factors = this.chunking$fusedInputFactors;
        double[] scales = this.chunking$fusedScales;
        Arrays.fill(out, 0, count, 0.0D);
        for (int i = 0; i < octs.length; i++) {
            ImprovedNoiseAccess oct = (ImprovedNoiseAccess) (Object) octs[i];
            double f = factors[i];
            NoiseMath.evalOctaveColumnNoSmear(
                    oct.chunking$perm(),
                    oct.chunking$xo(),
                    oct.chunking$yo(),
                    oct.chunking$zo(),
                    xFixed * f,
                    by,
                    yScale * f,
                    zFixed * f,
                    scales[i],
                    out,
                    count
            );
        }
    }

    @Override
    public void chunking$sampleColumnRange(
            double xFixed,
            int[] by,
            int startIdx,
            double yScale,
            double zFixed,
            double[] out,
            int totalCount
    ) {
        if (startIdx <= 0) {
            chunking$sampleColumnFast(xFixed, by, yScale, zFixed, out, totalCount);
            return;
        }
        int len = totalCount - startIdx;
        if (len <= 0) {
            return;
        }
        ImprovedNoise[] octs = this.chunking$fusedOctaves;
        if (octs == null) {
            chunking$initFused();
            octs = this.chunking$fusedOctaves;
        }
        java.lang.foreign.MemorySegment nativeOcts = this.chunking$nativeOcts;
        if (CHUNKING$NATIVE_AVX2_ENABLED && nativeOcts != null && len >= 4 && NativeAvx2.isAvailable()) {
            NativeAvx2.evalNormalColumnRange(nativeOcts, octs.length, xFixed, by, startIdx, yScale, zFixed, out, totalCount);
            return;
        }
        if (yScale == 0.0D) {
            double v = chunking$sampleNormalFast(xFixed, 0.0D, zFixed);
            Arrays.fill(out, startIdx, totalCount, v);
            return;
        }
        double[] factors = this.chunking$fusedInputFactors;
        double[] scales = this.chunking$fusedScales;
        Arrays.fill(out, startIdx, totalCount, 0.0D);
        for (int i = 0; i < octs.length; i++) {
            ImprovedNoiseAccess oct = (ImprovedNoiseAccess) (Object) octs[i];
            double f = factors[i];
            NoiseMath.evalOctaveColumnNoSmearRange(
                    oct.chunking$perm(),
                    oct.chunking$xo(),
                    oct.chunking$yo(),
                    oct.chunking$zo(),
                    xFixed * f,
                    by,
                    startIdx,
                    yScale * f,
                    zFixed * f,
                    scales[i],
                    out,
                    totalCount
            );
        }
    }

    @Override
    public void chunking$sampleBatch3DInt(
            int[] bx,
            int[] by,
            int[] bz,
            double xzScale,
            double yScale,
            double[] out,
            int count
    ) {
        ImprovedNoise[] octs = this.chunking$fusedOctaves;
        if (octs == null) {
            chunking$initFused();
            octs = this.chunking$fusedOctaves;
        }
        boolean isCell128 = false;
        if (count == 128) {
            isCell128 = true;
            for (int layer = 0; layer < 8; layer++) {
                int off = layer << 4;
                if (bx[off] != bx[0] || bz[off] != bz[0]
                        || bx[off + 15] != bx[15] || bz[off + 15] != bz[15]
                        || by[off] != by[off + 15]) {
                    isCell128 = false;
                    break;
                }
            }
        }
        if (yScale == 0.0D && isCell128) {
            java.lang.foreign.MemorySegment nativeOcts = this.chunking$nativeOcts;
            if (CHUNKING$NATIVE_AVX2_ENABLED && nativeOcts != null && NativeAvx2.isAvailable()) {
                NativeAvx2.evalNormalBatch3DInt(nativeOcts, octs.length, bx, by, bz, xzScale, 0.0D, out, 16);
            } else {
                for (int i = 0; i < 16; i++) {
                    out[i] = chunking$sampleNormalFast((double) bx[i] * xzScale, 0.0D, (double) bz[i] * xzScale);
                }
            }
            System.arraycopy(out, 0, out, 16, 16);
            System.arraycopy(out, 0, out, 32, 32);
            System.arraycopy(out, 0, out, 64, 64);
            return;
        }
        java.lang.foreign.MemorySegment nativeOcts = this.chunking$nativeOcts;
        if (CHUNKING$NATIVE_AVX2_ENABLED && nativeOcts != null && count >= 4 && NativeAvx2.isAvailable()) {
            NativeAvx2.evalNormalBatch3DInt(nativeOcts, octs.length, bx, by, bz, xzScale, yScale, out, count);
            return;
        }
        if (yScale == 0.0D) {
            for (int i = 0; i < count; i++) {
                out[i] = chunking$sampleNormalFast((double) bx[i] * xzScale, 0.0D, (double) bz[i] * xzScale);
            }
        } else if (isCell128 && bx[0] == bx[3] && bz[0] == bz[4]) {
            double[] factors = this.chunking$fusedInputFactors;
            double[] scales = this.chunking$fusedScales;
            Arrays.fill(out, 0, 128, 0.0D);
            for (int i = 0; i < octs.length; i++) {
                ImprovedNoiseAccess oct = (ImprovedNoiseAccess) (Object) octs[i];
                double f = factors[i];
                NoiseMath.evalOctaveCellGrid4x8x4(
                        oct.chunking$perm(),
                        oct.chunking$xo(),
                        oct.chunking$yo(),
                        oct.chunking$zo(),
                        bx,
                        by,
                        bz,
                        xzScale * f,
                        yScale * f,
                        scales[i],
                        out
                );
            }
        } else {
            for (int i = 0; i < count; i++) {
                out[i] = chunking$sampleNormalFast(
                        (double) bx[i] * xzScale,
                        (double) by[i] * yScale,
                        (double) bz[i] * xzScale
                );
            }
        }
    }

    @Override
    public ImprovedNoise[] chunking$fusedOctaves() {
        ImprovedNoise[] octs = this.chunking$fusedOctaves;
        if (octs == null) {
            chunking$initFused();
            octs = this.chunking$fusedOctaves;
        }
        return octs;
    }

    @Override
    public double[] chunking$fusedInputFactors() {
        if (this.chunking$fusedOctaves == null) {
            chunking$initFused();
        }
        return this.chunking$fusedInputFactors;
    }

    @Override
    public double[] chunking$fusedScales() {
        if (this.chunking$fusedOctaves == null) {
            chunking$initFused();
        }
        return this.chunking$fusedScales;
    }
}
