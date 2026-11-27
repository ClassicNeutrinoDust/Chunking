package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.ImprovedNoiseAccess;
import com.chunking.engine.simd.NoiseMath;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Flattens {@link ImprovedNoise} gradient lookups into three 16-element double tables
 * and supports X/Z-precomputed vertical column evaluation without {@code int[][]} chasing.
 */
@Mixin(ImprovedNoise.class)
public abstract class ImprovedNoiseMixin implements ImprovedNoiseAccess {
    @Unique
    private static final double[] CHUNKING$GX = {
            1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0
    };

    @Unique
    private static final double[] CHUNKING$GY = {
            1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1
    };

    @Unique
    private static final double[] CHUNKING$GZ = {
            0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1
    };

    @Shadow
    @Final
    private byte[] p;

    @Shadow
    @Final
    public double xo;

    @Shadow
    @Final
    public double yo;

    @Shadow
    @Final
    public double zo;

    @Shadow
    protected abstract double sampleAndLerp(
            int gridX,
            int gridY,
            int gridZ,
            double deltaX,
            double weirdDeltaY,
            double deltaZ,
            double deltaY
    );

    @Unique
    private static int chunking$floor(double v) {
        int i = (int) v;
        return v < (double) i ? i - 1 : i;
    }

    @Unique
    private static double chunking$smoothstep(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    @Unique
    private static double chunking$lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    @Unique
    private static double chunking$gradDot(int hash, double x, double y, double z) {
        int h = hash & 15;
        return CHUNKING$GX[h] * x + CHUNKING$GY[h] * y + CHUNKING$GZ[h] * z;
    }

    /**
     * @author Chunking
     * @reason Flatten 2D int[][] SimplexNoise.GRADIENT chase into L1-resident double[16] tables.
     */
    @Overwrite
    public double noise(double x, double y, double z, double yScale, double yMax) {
        if (!ModConfig.SIMD_NOISE) {
            double dx = x + this.xo;
            double dy = y + this.yo;
            double dz = z + this.zo;
            int ix = chunking$floor(dx);
            int iy = chunking$floor(dy);
            int iz = chunking$floor(dz);
            double xf = dx - (double) ix;
            double yf = dy - (double) iy;
            double zf = dz - (double) iz;
            double ys = 0.0D;
            if (yScale != 0.0D) {
                double c = yMax >= 0.0D && yMax < yf ? yMax : yf;
                ys = (double) chunking$floor(c / yScale + (double) 1.0E-7F) * yScale;
            }
            return this.sampleAndLerp(ix, iy, iz, xf, yf - ys, zf, yf);
        }
        double dx = x + this.xo;
        double dy = y + this.yo;
        double dz = z + this.zo;
        int ix = chunking$floor(dx);
        int iy = chunking$floor(dy);
        int iz = chunking$floor(dz);
        double xf = dx - (double) ix;
        double yf = dy - (double) iy;
        double zf = dz - (double) iz;
        double weirdY = yf;
        if (yScale != 0.0D) {
            double c = (yMax >= 0.0D && yMax < yf) ? yMax : yf;
            weirdY -= (double) chunking$floor(c / yScale + (double) 1.0E-7F) * yScale;
        }
        byte[] perm = this.p;
        int p0 = perm[ix & 255] & 255;
        int p1 = perm[(ix + 1) & 255] & 255;
        return chunking$sampleCore(perm, p0, p1, iy, iz, xf, chunking$smoothstep(xf), zf, chunking$smoothstep(zf), yf, weirdY);
    }

    @Override
    public byte[] chunking$perm() {
        return this.p;
    }

    @Override
    public double chunking$xo() {
        return this.xo;
    }

    @Override
    public double chunking$yo() {
        return this.yo;
    }

    @Override
    public double chunking$zo() {
        return this.zo;
    }

    @Unique
    private static double chunking$sampleCore(
            byte[] perm,
            int p0,
            int p1,
            int iy,
            int iz,
            double xf,
            double u,
            double zf,
            double w,
            double yf,
            double weirdY
    ) {
        int k = perm[(p0 + iy) & 255] & 255;
        int l = perm[(p0 + iy + 1) & 255] & 255;
        int m = perm[(p1 + iy) & 255] & 255;
        int n = perm[(p1 + iy + 1) & 255] & 255;

        double xf1 = xf - 1.0D;
        double wy1 = weirdY - 1.0D;
        double zf1 = zf - 1.0D;

        int iz0 = iz & 255;
        int iz1 = (iz + 1) & 255;

        double d000 = chunking$gradDot(perm[(k + iz0) & 255] & 255, xf, weirdY, zf);
        double d100 = chunking$gradDot(perm[(m + iz0) & 255] & 255, xf1, weirdY, zf);
        double d010 = chunking$gradDot(perm[(l + iz0) & 255] & 255, xf, wy1, zf);
        double d110 = chunking$gradDot(perm[(n + iz0) & 255] & 255, xf1, wy1, zf);
        double d001 = chunking$gradDot(perm[(k + iz1) & 255] & 255, xf, weirdY, zf1);
        double d101 = chunking$gradDot(perm[(m + iz1) & 255] & 255, xf1, weirdY, zf1);
        double d011 = chunking$gradDot(perm[(l + iz1) & 255] & 255, xf, wy1, zf1);
        double d111 = chunking$gradDot(perm[(n + iz1) & 255] & 255, xf1, wy1, zf1);

        double v = chunking$smoothstep(yf);
        double x00 = chunking$lerp(u, d000, d100);
        double x10 = chunking$lerp(u, d010, d110);
        double x01 = chunking$lerp(u, d001, d101);
        double x11 = chunking$lerp(u, d011, d111);
        double xy0 = chunking$lerp(v, x00, x10);
        double xy1 = chunking$lerp(v, x01, x11);
        return chunking$lerp(w, xy0, xy1);
    }

    @Unique
    public void chunking$sampleBatch(
            double[] xs,
            double[] ys,
            double[] zs,
            double yScale,
            double yMax,
            double[] out,
            int count
    ) {
        NoiseMath.sampleBatch3D(
                this.p, this.xo, this.yo, this.zo, xs, ys, zs, yScale, yMax, out, count
        );
    }
}
