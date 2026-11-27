package com.chunking.engine.simd;

/**
 * Register-fused Perlin/ImprovedNoise evaluator eliminating intermediate heap scratch array traffic
 * while preserving exact Minecraft 26.2 permutation/gradient/smoothstep math to < 1e-12 ULP.
 */
public final class NoiseMath {
    public static final int LANES = 4;

    private static final double WRAP_PERIOD = 3.3554432E7D;
    private static final double SHIFT_UP_EPSILON = 1.0000000116860974E-7D;

    private static final class CellGridScratch {
        final int[] ix = new int[4];
        final int[] p0 = new int[4];
        final int[] p1 = new int[4];
        final double[] xf = new double[4];
        final double[] xf1 = new double[4];
        final double[] u = new double[4];
        final int[] iz0 = new int[4];
        final int[] iz1 = new int[4];
        final double[] zf = new double[4];
        final double[] zf1 = new double[4];
        final double[] w = new double[4];
    }

    private static final ThreadLocal<CellGridScratch> CELL_GRID_TL = ThreadLocal.withInitial(CellGridScratch::new);

    private NoiseMath() {}

    /**
     * Batch-samples an {@code ImprovedNoise} octave across {@code count} 3D coordinates directly in CPU registers.
     */
    public static void sampleBatch3D(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double[] xs,
            double[] ys,
            double[] zs,
            double[] out,
            int count
    ) {
        sampleBatch3D(perm, xo, yo, zo, xs, ys, zs, 0.0D, 0.0D, out, count);
    }

    /**
     * Batch-samples an {@code ImprovedNoise} octave with Minecraft's {@code yScale}/{@code yMax} clamping
     * directly in CPU registers without intermediate array allocations or loads/stores.
     */
    public static void sampleBatch3D(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double[] xs,
            double[] ys,
            double[] zs,
            double yScale,
            double yMax,
            double[] out,
            int count
    ) {
        if (perm == null || perm.length < 256) throw new IllegalArgumentException("perm");
        if (count <= 0) return;

        if (yScale == 0.0D) {
            int i = 0;
            int unrollEnd = count & ~3;
            for (; i < unrollEnd; i += 4) {
                out[i] = sampleFastNoYScale(perm, xs[i] + xo, ys[i] + yo, zs[i] + zo);
                out[i + 1] = sampleFastNoYScale(perm, xs[i + 1] + xo, ys[i + 1] + yo, zs[i + 1] + zo);
                out[i + 2] = sampleFastNoYScale(perm, xs[i + 2] + xo, ys[i + 2] + yo, zs[i + 2] + zo);
                out[i + 3] = sampleFastNoYScale(perm, xs[i + 3] + xo, ys[i + 3] + yo, zs[i + 3] + zo);
            }
            for (; i < count; i++) {
                out[i] = sampleFastNoYScale(perm, xs[i] + xo, ys[i] + yo, zs[i] + zo);
            }
            return;
        }

        for (int i = 0; i < count; i++) {
            out[i] = sampleScalar(perm, xo, yo, zo, xs[i], ys[i], zs[i], yScale, yMax);
        }
    }

    private static double sampleFastNoYScale(byte[] perm, double d, double e, double f) {
        int ix = floor(d);
        int iy = floor(e);
        int iz = floor(f);
        double dx = d - ix;
        double dy = e - iy;
        double dz = f - iz;
        return sampleAndLerp(perm, ix, iy, iz, dx, dy, dz, dy);
    }

    /**
     * Exact Minecraft {@code ImprovedNoise.noise(x, y, z, yScale, yMax)} scalar computation.
     */
    public static double sampleScalar(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double x,
            double y,
            double z,
            double yScale,
            double yMax
    ) {
        double d = x + xo;
        double e = y + yo;
        double f = z + zo;
        int i = floor(d);
        int j = floor(e);
        int k = floor(f);
        double g = d - i;
        double h = e - j;
        double l = f - k;
        double m;
        if (yScale != 0.0D) {
            double n = (yMax >= 0.0D && yMax < h) ? yMax : h;
            m = floor(n / yScale + SHIFT_UP_EPSILON) * yScale;
        } else {
            m = 0.0D;
        }
        return sampleAndLerp(perm, i, j, k, g, h - m, l, h);
    }

    public static double wrap(double value) {
        return value - (double) floorLong(value / WRAP_PERIOD + 0.5D) * WRAP_PERIOD;
    }

    private static double sampleAndLerp(
            byte[] perm,
            int x,
            int y,
            int z,
            double dx,
            double dy,
            double dz,
            double originalDy
    ) {
        int a = perm[x & 255] & 255;
        int b = perm[(x + 1) & 255] & 255;
        int aa = perm[(a + y) & 255] & 255;
        int ab = perm[(a + y + 1) & 255] & 255;
        int ba = perm[(b + y) & 255] & 255;
        int bb = perm[(b + y + 1) & 255] & 255;

        double c000 = gradDot(perm[(aa + z) & 255] & 15, dx, dy, dz);
        double c100 = gradDot(perm[(ba + z) & 255] & 15, dx - 1.0D, dy, dz);
        double c010 = gradDot(perm[(ab + z) & 255] & 15, dx, dy - 1.0D, dz);
        double c110 = gradDot(perm[(bb + z) & 255] & 15, dx - 1.0D, dy - 1.0D, dz);
        double c001 = gradDot(perm[(aa + z + 1) & 255] & 15, dx, dy, dz - 1.0D);
        double c101 = gradDot(perm[(ba + z + 1) & 255] & 15, dx - 1.0D, dy, dz - 1.0D);
        double c011 = gradDot(perm[(ab + z + 1) & 255] & 15, dx, dy - 1.0D, dz - 1.0D);
        double c111 = gradDot(perm[(bb + z + 1) & 255] & 15, dx - 1.0D, dy - 1.0D, dz - 1.0D);

        double u = smoothstepScalar(dx);
        double v = smoothstepScalar(originalDy);
        double w = smoothstepScalar(dz);

        double x00 = lerp(u, c000, c100);
        double x10 = lerp(u, c010, c110);
        double x01 = lerp(u, c001, c101);
        double x11 = lerp(u, c011, c111);

        double y0 = lerp(v, x00, x10);
        double y1 = lerp(v, x01, x11);
        return lerp(w, y0, y1);
    }

    private static double gradDot(int g, double x, double y, double z) {
        return K_GX[g] * x + K_GY[g] * y + K_GZ[g] * z;
    }

    private static final double[] K_GX = { 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0 };
    private static final double[] K_GY = { 1, 1, -1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1 };
    private static final double[] K_GZ = { 0, 0, 0, 0, 1, 1, -1, -1, 1, 1, -1, -1, 0, 1, 0, -1 };

    /**
     * Evaluates a single {@code ImprovedNoise} octave across a standard {@code 4x8x4} (128-voxel)
     * cell grid where {@code by[y*16]}, {@code bx[x*4]}, and {@code bz[z]} form a separable 3D product grid,
     * reducing {@code wrap}, {@code floor}, and {@code smoothstep} evaluations by 24x while preserving
     * bit-exact IEEE-754 parity with {@link #sampleFastNoYScale}.
     */
    public static void evalOctaveCellGrid4x8x4(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            int[] bx,
            int[] by,
            int[] bz,
            double xzFactor,
            double yFactor,
            double weight,
            double[] acc
    ) {
        CellGridScratch sc = CELL_GRID_TL.get();
        int[] ix = sc.ix;
        int[] p0 = sc.p0;
        int[] p1 = sc.p1;
        double[] xf = sc.xf;
        double[] xf1 = sc.xf1;
        double[] u = sc.u;
        int[] iz0 = sc.iz0;
        int[] iz1 = sc.iz1;
        double[] zf = sc.zf;
        double[] zf1 = sc.zf1;
        double[] w = sc.w;

        for (int x = 0; x < 4; x++) {
            double xc = bx[x << 2] * xzFactor;
            double wx = (xc > -1.6777215E7D && xc < 1.6777215E7D) ? xc : wrap(xc);
            double dx = wx + xo;
            int fx = floor(dx);
            double rem = dx - fx;
            ix[x] = fx;
            p0[x] = perm[fx & 255] & 255;
            p1[x] = perm[(fx + 1) & 255] & 255;
            xf[x] = rem;
            xf1[x] = rem - 1.0D;
            u[x] = smoothstepScalar(rem);
        }

        for (int z = 0; z < 4; z++) {
            double zc = bz[z] * xzFactor;
            double wz = (zc > -1.6777215E7D && zc < 1.6777215E7D) ? zc : wrap(zc);
            double dz = wz + zo;
            int fz = floor(dz);
            double rem = dz - fz;
            iz0[z] = fz & 255;
            iz1[z] = (fz + 1) & 255;
            zf[z] = rem;
            zf1[z] = rem - 1.0D;
            w[z] = smoothstepScalar(rem);
        }

        int idx = 0;
        for (int y = 0; y < 8; y++) {
            double yc = by[y << 4] * yFactor;
            double wy = (yc > -1.6777215E7D && yc < 1.6777215E7D) ? yc : wrap(yc);
            double dy = wy + yo;
            int iy = floor(dy);
            double yf = dy - iy;
            double yf1 = yf - 1.0D;
            double v = smoothstepScalar(yf);

            for (int x = 0; x < 4; x++) {
                int a = p0[x];
                int b = p1[x];
                int aa = perm[(a + iy) & 255] & 255;
                int ab = perm[(a + iy + 1) & 255] & 255;
                int ba = perm[(b + iy) & 255] & 255;
                int bb = perm[(b + iy + 1) & 255] & 255;
                double dxf = xf[x];
                double dxf1 = xf1[x];
                double ux = u[x];

                for (int z = 0; z < 4; z++) {
                    int z0 = iz0[z];
                    int z1 = iz1[z];
                    double dzf = zf[z];
                    double dzf1 = zf1[z];
                    double c000 = gradDot(perm[(aa + z0) & 255] & 15, dxf, yf, dzf);
                    double c100 = gradDot(perm[(ba + z0) & 255] & 15, dxf1, yf, dzf);
                    double c010 = gradDot(perm[(ab + z0) & 255] & 15, dxf, yf1, dzf);
                    double c110 = gradDot(perm[(bb + z0) & 255] & 15, dxf1, yf1, dzf);
                    double c001 = gradDot(perm[(aa + z1) & 255] & 15, dxf, yf, dzf1);
                    double c101 = gradDot(perm[(ba + z1) & 255] & 15, dxf1, yf, dzf1);
                    double c011 = gradDot(perm[(ab + z1) & 255] & 15, dxf, yf1, dzf1);
                    double c111 = gradDot(perm[(bb + z1) & 255] & 15, dxf1, yf1, dzf1);

                    double x00 = lerp(ux, c000, c100);
                    double x10 = lerp(ux, c010, c110);
                    double x01 = lerp(ux, c001, c101);
                    double x11 = lerp(ux, c011, c111);
                    acc[idx++] += lerp(w[z], lerp(v, x00, x10), lerp(v, x01, x11)) * weight;
                }
            }
        }
    }

    /**
     * Evaluates a single {@code ImprovedNoise} octave (zero y-smear, as in {@code NormalNoise})
     * across a constant-{@code (x, z)} vertical column {@code by[0..count-1]}, hoisting all X/Z
     * wrapping, permutation lookups, and lattice-constant gradient blends across consecutive Y levels.
     */
    public static void evalOctaveColumnNoSmear(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double xCoord,
            int[] by,
            double yFactor,
            double zCoord,
            double weight,
            double[] acc,
            int count
    ) {
        evalOctaveColumnNoSmearRange(perm, xo, yo, zo, xCoord, by, 0, yFactor, zCoord, weight, acc, count);
    }

    public static void evalOctaveColumnNoSmearRange(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double xCoord,
            int[] by,
            int startIdx,
            double yFactor,
            double zCoord,
            double weight,
            double[] acc,
            int totalCount
    ) {
        double wx = (xCoord > -1.6777215E7D && xCoord < 1.6777215E7D) ? xCoord : wrap(xCoord);
        double wz = (zCoord > -1.6777215E7D && zCoord < 1.6777215E7D) ? zCoord : wrap(zCoord);
        double dx = wx + xo;
        double dz = wz + zo;
        int ix = floor(dx);
        int iz = floor(dz);
        double xf = dx - ix;
        double zf = dz - iz;
        double xf1 = xf - 1.0D;
        double zf1 = zf - 1.0D;
        double u = smoothstepScalar(xf);
        double w = smoothstepScalar(zf);
        int p0 = perm[ix & 255] & 255;
        int p1 = perm[(ix + 1) & 255] & 255;
        int iz0 = iz & 255;
        int iz1 = (iz + 1) & 255;

        if (yFactor == 0.0D) {
            int iy = floor(yo);
            double yf = yo - iy;
            int k = perm[(p0 + iy) & 255] & 255;
            int l = perm[(p0 + iy + 1) & 255] & 255;
            int m = perm[(p1 + iy) & 255] & 255;
            int n = perm[(p1 + iy + 1) & 255] & 255;
            double v = smoothstepScalar(yf);
            double x00 = lerp(u, gradDot(perm[(k + iz0) & 255] & 15, xf, yf, zf), gradDot(perm[(m + iz0) & 255] & 15, xf1, yf, zf));
            double x10 = lerp(u, gradDot(perm[(l + iz0) & 255] & 15, xf, yf - 1.0D, zf), gradDot(perm[(n + iz0) & 255] & 15, xf1, yf - 1.0D, zf));
            double x01 = lerp(u, gradDot(perm[(k + iz1) & 255] & 15, xf, yf, zf1), gradDot(perm[(m + iz1) & 255] & 15, xf1, yf, zf1));
            double x11 = lerp(u, gradDot(perm[(l + iz1) & 255] & 15, xf, yf - 1.0D, zf1), gradDot(perm[(n + iz1) & 255] & 15, xf1, yf - 1.0D, zf1));
            double delta = lerp(w, lerp(v, x00, x10), lerp(v, x01, x11)) * weight;
            for (int i = startIdx; i < totalCount; i++) {
                acc[i] += delta;
            }
            return;
        }

        int prevIy = Integer.MIN_VALUE;
        double lc0 = 0.0D, ls0 = 0.0D, lc1 = 0.0D, ls1 = 0.0D;
        for (int i = startIdx; i < totalCount; i++) {
            double ys = by[i] * yFactor;
            double wy = (ys > -1.6777215E7D && ys < 1.6777215E7D) ? ys : wrap(ys);
            double dy = wy + yo;
            int iy = floor(dy);
            double yf = dy - iy;
            if (iy != prevIy) {
                prevIy = iy;
                int k = perm[(p0 + iy) & 255] & 255;
                int l = perm[(p0 + iy + 1) & 255] & 255;
                int m = perm[(p1 + iy) & 255] & 255;
                int n = perm[(p1 + iy + 1) & 255] & 255;

                int h000 = perm[(k + iz0) & 255] & 15;
                int h100 = perm[(m + iz0) & 255] & 15;
                int h010 = perm[(l + iz0) & 255] & 15;
                int h110 = perm[(n + iz0) & 255] & 15;
                int h001 = perm[(k + iz1) & 255] & 15;
                int h101 = perm[(m + iz1) & 255] & 15;
                int h011 = perm[(l + iz1) & 255] & 15;
                int h111 = perm[(n + iz1) & 255] & 15;

                double b000 = K_GX[h000] * xf + K_GZ[h000] * zf, s000 = K_GY[h000];
                double b100 = K_GX[h100] * xf1 + K_GZ[h100] * zf, s100 = K_GY[h100];
                double s010 = K_GY[h010], b010 = K_GX[h010] * xf + K_GZ[h010] * zf - s010;
                double s110 = K_GY[h110], b110 = K_GX[h110] * xf1 + K_GZ[h110] * zf - s110;
                double b001 = K_GX[h001] * xf + K_GZ[h001] * zf1, s001 = K_GY[h001];
                double b101 = K_GX[h101] * xf1 + K_GZ[h101] * zf1, s101 = K_GY[h101];
                double s011 = K_GY[h011], b011 = K_GX[h011] * xf + K_GZ[h011] * zf1 - s011;
                double s111 = K_GY[h111], b111 = K_GX[h111] * xf1 + K_GZ[h111] * zf1 - s111;

                lc0 = lerp(w, lerp(u, b000, b100), lerp(u, b001, b101));
                ls0 = lerp(w, lerp(u, s000, s100), lerp(u, s001, s101));
                lc1 = lerp(w, lerp(u, b010, b110), lerp(u, b011, b111));
                ls1 = lerp(w, lerp(u, s010, s110), lerp(u, s011, s111));
            }
            double v = smoothstepScalar(yf);
            double y0 = lc0 + ls0 * yf;
            double y1 = lc1 + ls1 * yf;
            acc[i] += lerp(v, y0, y1) * weight;
        }
    }

    /**
     * Evaluates a single {@code ImprovedNoise} octave with {@code yScale} smear (for {@code BlendedNoise})
     * across a constant-{@code (x, z)} vertical column {@code by[0..count-1]}, hoisting all X/Z wrap,
     * floor, smoothstep, and outer permutation lookups outside the Y loop.
     *
     * <p>{@code maskMode}: {@code 0}=unconditional, {@code 1}=skip {@code blend >= 1.0}, {@code 2}=skip {@code blend <= 0.0}.
     */
    public static void evalOctaveColumnWithSmear(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double xCoord,
            int[] by,
            double yFactor,
            double zCoord,
            double smearYScale,
            double invWeight,
            double[] acc,
            int count,
            int maskMode,
            double[] blendArr
    ) {
        evalOctaveColumnWithSmearRange(
                perm, xo, yo, zo, xCoord, by, 0, yFactor, zCoord, smearYScale, invWeight, acc, count, maskMode, blendArr);
    }

    public static void evalOctaveColumnWithSmearRange(
            byte[] perm,
            double xo,
            double yo,
            double zo,
            double xCoord,
            int[] by,
            int startIdx,
            double yFactor,
            double zCoord,
            double smearYScale,
            double invWeight,
            double[] acc,
            int totalCount,
            int maskMode,
            double[] blendArr
    ) {
        double wx = (xCoord > -1.6777215E7D && xCoord < 1.6777215E7D) ? xCoord : wrap(xCoord);
        double wz = (zCoord > -1.6777215E7D && zCoord < 1.6777215E7D) ? zCoord : wrap(zCoord);
        double dx = wx + xo;
        double dz = wz + zo;
        int ix = floor(dx);
        int iz = floor(dz);
        double xf = dx - ix;
        double zf = dz - iz;
        double xf1 = xf - 1.0D;
        double zf1 = zf - 1.0D;
        double u = smoothstepScalar(xf);
        double w = smoothstepScalar(zf);
        int p0 = perm[ix & 255] & 255;
        int p1 = perm[(ix + 1) & 255] & 255;
        int iz0 = iz & 255;
        int iz1 = (iz + 1) & 255;

        final boolean hasSmear = smearYScale != 0.0D;
        final double invSmearYScale = hasSmear ? (1.0D / smearYScale) : 0.0D;
        int prevIy = Integer.MIN_VALUE;
        double prevWeirdY = Double.NaN;
        double xz000 = 0.0D, xz100 = 0.0D, xz010 = 0.0D, xz110 = 0.0D;
        double xz001 = 0.0D, xz101 = 0.0D, xz011 = 0.0D, xz111 = 0.0D;
        double gy000 = 0.0D, gy100 = 0.0D, gy010 = 0.0D, gy110 = 0.0D;
        double gy001 = 0.0D, gy101 = 0.0D, gy011 = 0.0D, gy111 = 0.0D;
        double x00 = 0.0D, x10 = 0.0D, x01 = 0.0D, x11 = 0.0D;

        for (int i = startIdx; i < totalCount; i++) {
            if (maskMode == 1 && blendArr[i] >= 1.0D) continue;
            if (maskMode == 2 && blendArr[i] <= 0.0D) continue;

            double scaledY = by[i] * yFactor;
            double wy = (scaledY > -1.6777215E7D && scaledY < 1.6777215E7D) ? scaledY : wrap(scaledY);
            double dy = wy + yo;
            int iy = floor(dy);
            double yf = dy - iy;
            double weirdY;
            if (hasSmear) {
                double c = (scaledY >= 0.0D && scaledY < yf) ? scaledY : yf;
                weirdY = yf - (double) floor(c * invSmearYScale + SHIFT_UP_EPSILON) * smearYScale;
            } else {
                weirdY = yf;
            }

            if (iy != prevIy) {
                prevIy = iy;
                prevWeirdY = Double.NaN;
                int k = perm[(p0 + iy) & 255] & 255;
                int l = perm[(p0 + iy + 1) & 255] & 255;
                int m = perm[(p1 + iy) & 255] & 255;
                int n = perm[(p1 + iy + 1) & 255] & 255;
                int h000 = perm[(k + iz0) & 255] & 15;
                int h100 = perm[(m + iz0) & 255] & 15;
                int h010 = perm[(l + iz0) & 255] & 15;
                int h110 = perm[(n + iz0) & 255] & 15;
                int h001 = perm[(k + iz1) & 255] & 15;
                int h101 = perm[(m + iz1) & 255] & 15;
                int h011 = perm[(l + iz1) & 255] & 15;
                int h111 = perm[(n + iz1) & 255] & 15;

                xz000 = K_GX[h000] * xf  + K_GZ[h000] * zf;  gy000 = K_GY[h000];
                xz100 = K_GX[h100] * xf1 + K_GZ[h100] * zf;  gy100 = K_GY[h100];
                xz010 = K_GX[h010] * xf  + K_GZ[h010] * zf;  gy010 = K_GY[h010];
                xz110 = K_GX[h110] * xf1 + K_GZ[h110] * zf;  gy110 = K_GY[h110];
                xz001 = K_GX[h001] * xf  + K_GZ[h001] * zf1; gy001 = K_GY[h001];
                xz101 = K_GX[h101] * xf1 + K_GZ[h101] * zf1; gy101 = K_GY[h101];
                xz011 = K_GX[h011] * xf  + K_GZ[h011] * zf1; gy011 = K_GY[h011];
                xz111 = K_GX[h111] * xf1 + K_GZ[h111] * zf1; gy111 = K_GY[h111];
            }

            if (weirdY != prevWeirdY) {
                prevWeirdY = weirdY;
                double wy1 = weirdY - 1.0D;
                x00 = lerp(u, xz000 + gy000 * weirdY, xz100 + gy100 * weirdY);
                x10 = lerp(u, xz010 + gy010 * wy1,    xz110 + gy110 * wy1);
                x01 = lerp(u, xz001 + gy001 * weirdY, xz101 + gy101 * weirdY);
                x11 = lerp(u, xz011 + gy011 * wy1,    xz111 + gy111 * wy1);
            }
            double v = smoothstepScalar(yf);
            acc[i] += lerp(w, lerp(v, x00, x10), lerp(v, x01, x11)) * invWeight;
        }
    }

    private static double smoothstepScalar(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static long floorLong(double v) {
        long l = (long) v;
        return v < l ? l - 1L : l;
    }
}
