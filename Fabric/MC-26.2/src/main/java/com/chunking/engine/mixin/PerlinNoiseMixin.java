package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.PerlinNoiseAccess;
import com.chunking.engine.simd.NoiseMath;
import it.unimi.dsi.fastutil.doubles.DoubleList;
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

/**
 * Compacts active non-null octaves with pre-multiplied amplitude*value scales on construction
 * and uses unboxed {@code @Overwrite} paths to avoid all {@code Double} boxing overhead.
 */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseMixin implements PerlinNoiseAccess {
    @Shadow
    @Final
    private ImprovedNoise[] noiseLevels;

    @Shadow
    @Final
    private DoubleList amplitudes;

    @Shadow
    @Final
    private double lowestFreqInputFactor;

    @Shadow
    @Final
    private double lowestFreqValueFactor;

    @Unique
    private volatile ImprovedNoise[] chunking$activeOctaves;

    @Unique
    private double[] chunking$activeInputFactors;

    @Unique
    private double[] chunking$activeCombinedScales;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$compactActiveOctaves(CallbackInfo ci) {
        chunking$initCompactOctaves();
    }

    @Unique
    private synchronized void chunking$initCompactOctaves() {
        if (this.chunking$activeOctaves != null) {
            return;
        }
        ImprovedNoise[] levels = this.noiseLevels;
        int len = levels.length;
        int activeCount = 0;
        for (int i = 0; i < len; i++) {
            if (levels[i] != null) {
                activeCount++;
            }
        }
        ImprovedNoise[] outOctaves = new ImprovedNoise[activeCount];
        double[] outInputFactors = new double[activeCount];
        double[] outCombinedScales = new double[activeCount];

        double factor = this.lowestFreqInputFactor;
        double valueFactor = this.lowestFreqValueFactor;
        int idx = 0;
        for (int i = 0; i < len; i++) {
            ImprovedNoise octave = levels[i];
            if (octave != null) {
                outOctaves[idx] = octave;
                outInputFactors[idx] = factor;
                outCombinedScales[idx] = this.amplitudes.getDouble(i) * valueFactor;
                idx++;
            }
            factor *= 2.0D;
            valueFactor /= 2.0D;
        }
        this.chunking$activeInputFactors = outInputFactors;
        this.chunking$activeCombinedScales = outCombinedScales;
        this.chunking$activeOctaves = outOctaves;
    }

    @Unique
    private static double chunking$fastWrap(double v) {
        return (v > -1.6777215E7D && v < 1.6777215E7D) ? v : PerlinNoise.wrap(v);
    }

    /**
     * @author Chunking
     * @reason Eliminate CallbackInfoReturnable&lt;Double&gt; heap boxing and octave null/scale overhead.
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        return this.getValue(x, y, z, 0.0D, 0.0D);
    }

    /**
     * @author Chunking
     * @reason Eliminate CallbackInfoReturnable&lt;Double&gt; heap boxing and octave null/scale overhead.
     */
    @Overwrite
    public double getValue(double x, double y, double z, double yScale, double yMax) {
        if (!ModConfig.SIMD_NOISE) {
            double val = 0.0D;
            double factor = this.lowestFreqInputFactor;
            double valueFactor = this.lowestFreqValueFactor;
            for (int i = 0; i < this.noiseLevels.length; i++) {
                ImprovedNoise octave = this.noiseLevels[i];
                if (octave != null) {
                    double n = octave.noise(
                            PerlinNoise.wrap(x * factor),
                            PerlinNoise.wrap(y * factor),
                            PerlinNoise.wrap(z * factor),
                            yScale * factor,
                            yMax * factor
                    );
                    val += this.amplitudes.getDouble(i) * n * valueFactor;
                }
                factor *= 2.0D;
                valueFactor /= 2.0D;
            }
            return val;
        }

        ImprovedNoise[] octaves = this.chunking$activeOctaves;
        if (octaves == null) {
            chunking$initCompactOctaves();
            octaves = this.chunking$activeOctaves;
        }
        double[] inFactors = this.chunking$activeInputFactors;
        double[] combinedScales = this.chunking$activeCombinedScales;

        double value = 0.0D;
        if (yScale == 0.0D && yMax == 0.0D) {
            for (int i = 0; i < octaves.length; i++) {
                double f = inFactors[i];
                double n = octaves[i].noise(
                        chunking$fastWrap(x * f),
                        chunking$fastWrap(y * f),
                        chunking$fastWrap(z * f),
                        0.0D,
                        0.0D
                );
                value += n * combinedScales[i];
            }
            return value;
        }
        for (int i = 0; i < octaves.length; i++) {
            double f = inFactors[i];
            double n = octaves[i].noise(
                    chunking$fastWrap(x * f),
                    chunking$fastWrap(y * f),
                    chunking$fastWrap(z * f),
                    yScale * f,
                    yMax * f
            );
            value += n * combinedScales[i];
        }
        return value;
    }

    @Unique
    public double chunking$lowestFreqInputFactor() {
        return this.lowestFreqInputFactor;
    }

    @Unique
    public double chunking$lowestFreqValueFactor() {
        return this.lowestFreqValueFactor;
    }

    @Unique
    public int chunking$octaveCount() {
        return this.noiseLevels.length;
    }

    @Unique
    public double chunking$wrapSimdCoord(double value) {
        return NoiseMath.wrap(value);
    }

    @Override
    public ImprovedNoise[] chunking$activeOctaves() {
        if (this.chunking$activeOctaves == null) {
            chunking$initCompactOctaves();
        }
        return this.chunking$activeOctaves;
    }

    @Override
    public double[] chunking$activeInputFactors() {
        if (this.chunking$activeOctaves == null) {
            chunking$initCompactOctaves();
        }
        return this.chunking$activeInputFactors;
    }

    @Override
    public double[] chunking$activeCombinedScales() {
        if (this.chunking$activeOctaves == null) {
            chunking$initCompactOctaves();
        }
        return this.chunking$activeCombinedScales;
    }
}
