#include <immintrin.h>
#include <math.h>
#include <stdint.h>

#ifdef _MSC_VER
#include <intrin.h>
#pragma fp_contract(off)
#pragma float_control(precise, on)
#define CHUNKING_FORCEINLINE __forceinline
#define CHUNKING_EXPORT __declspec(dllexport)
#else
#define CHUNKING_FORCEINLINE __attribute__((always_inline)) inline
#define CHUNKING_EXPORT __attribute__((visibility("default")))
#endif

extern "C" {

// ---- 1. CPU feature check ----

// Scalar CPUID + OSXSAVE guard so non-AVX2 CPUs fall back to Java without SIGILL.
#ifndef _MSC_VER
__attribute__((target("no-avx,no-avx2")))
#endif
CHUNKING_EXPORT int32_t chunking_avx2_cpu_supported() {
#ifdef _MSC_VER
    int info[4] = {0, 0, 0, 0};
    __cpuid(info, 0);
    if (info[0] < 7) return 0;
    __cpuid(info, 1);
    if ((info[2] & (1 << 27)) == 0 || (info[2] & (1 << 28)) == 0) return 0;
    if ((_xgetbv(0) & 0x6ULL) != 0x6ULL) return 0;
    __cpuidex(info, 7, 0);
    return (info[1] & (1 << 5)) != 0 ? 1 : 0;
#else
    uint32_t eax = 0, ebx = 0, ecx = 0, edx = 0;
    __asm__ __volatile__("cpuid" : "=a"(eax), "=b"(ebx), "=c"(ecx), "=d"(edx) : "a"(0), "c"(0));
    if (eax < 7u) return 0;
    __asm__ __volatile__("cpuid" : "=a"(eax), "=b"(ebx), "=c"(ecx), "=d"(edx) : "a"(1), "c"(0));
    if ((ecx & (1u << 27)) == 0 || (ecx & (1u << 28)) == 0) return 0;
    uint32_t xcr0Lo = 0, xcr0Hi = 0;
    __asm__ __volatile__(".byte 0x0f, 0x01, 0xd0" : "=a"(xcr0Lo), "=d"(xcr0Hi) : "c"(0));
    if ((xcr0Lo & 0x6u) != 0x6u) return 0;
    __asm__ __volatile__("cpuid" : "=a"(eax), "=b"(ebx), "=c"(ecx), "=d"(edx) : "a"(7), "c"(0));
    return (ebx & (1u << 5)) != 0 ? 1 : 0;
#endif
}

// ---- 2. Shared ABI structs & math helpers ----

// Must match NativeAvx2 octave layout (576 bytes, 32-byte aligned).
struct alignas(32) OctaveDesc {
    double factor;
    double scale;
    double xo;
    double yo;
    double zo;
    double _pad;
    uint8_t perm[512];
};

// Must match NativeAvx2 blended octave layout (576 bytes, 32-byte aligned).
struct alignas(32) BlendedOctaveDesc {
    double xzScale;
    double yFactor;
    double smearYScale;
    double invWeight;
    double xo;
    double yo;
    double zo;
    double invSmearYScale;
    uint8_t perm[512];
};

static_assert(sizeof(OctaveDesc) == 576, "OctaveDesc must be 576 bytes");
static_assert(sizeof(BlendedOctaveDesc) == 576, "BlendedOctaveDesc must be 576 bytes");

static const double GX[16] = { 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, 0, -1, 0 };
static const double GY[16] = { 1,  1,-1, -1, 0,  0, 0,  0, 1,-1, 1,-1, 1,-1,  1,-1 };
static const double GZ[16] = { 0,  0, 0,  0, 1,  1,-1, -1, 1, 1,-1,-1, 0, 1,  0,-1 };
static const double SHIFT_UP_EPSILON = 1.0000000116860974E-7;

static CHUNKING_FORCEINLINE int32_t fast_floor(double v) {
    int32_t i = (int32_t)v;
    return v < (double)i ? i - 1 : i;
}

static CHUNKING_FORCEINLINE double wrap_coord(double v) {
    if (v > -1.6777215E7 && v < 1.6777215E7) return v;
    return v - floor(v / 3.3554432E7 + 0.5) * 3.3554432E7;
}

static CHUNKING_FORCEINLINE __m256d wrap_coord4(__m256d v) {
    const __m256d limLo = _mm256_set1_pd(-1.6777215E7);
    const __m256d limHi = _mm256_set1_pd(1.6777215E7);
    __m256d ok = _mm256_and_pd(
        _mm256_cmp_pd(v, limLo, _CMP_GT_OQ),
        _mm256_cmp_pd(v, limHi, _CMP_LT_OQ));
    if (_mm256_movemask_pd(ok) == 0xF) return v;

    const __m256d period = _mm256_set1_pd(3.3554432E7);
    const __m256d invPeriod = _mm256_set1_pd(1.0 / 3.3554432E7);
    const __m256d half = _mm256_set1_pd(0.5);
    __m256d q = _mm256_floor_pd(_mm256_add_pd(_mm256_mul_pd(v, invPeriod), half));
    return _mm256_blendv_pd(_mm256_sub_pd(v, _mm256_mul_pd(q, period)), v, ok);
}

static CHUNKING_FORCEINLINE double smoothstep_d(double t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

static CHUNKING_FORCEINLINE double lerp_d(double t, double a, double b) {
    return a + t * (b - a);
}

static CHUNKING_FORCEINLINE __m256d smoothstep4(__m256d t) {
    const __m256d c6 = _mm256_set1_pd(6.0);
    const __m256d c15 = _mm256_set1_pd(15.0);
    const __m256d c10 = _mm256_set1_pd(10.0);
    __m256d t2 = _mm256_mul_pd(t, t);
    __m256d t3 = _mm256_mul_pd(t2, t);
    __m256d inner = _mm256_sub_pd(_mm256_mul_pd(t, c6), c15);
    return _mm256_mul_pd(t3, _mm256_add_pd(_mm256_mul_pd(t, inner), c10));
}

static CHUNKING_FORCEINLINE __m256d lerp4(__m256d t, __m256d a, __m256d b) {
    return _mm256_add_pd(a, _mm256_mul_pd(t, _mm256_sub_pd(b, a)));
}

static CHUNKING_FORCEINLINE double grad_dot(int h, double x, double y, double z) {
    return (GX[h] * x + GY[h] * y) + GZ[h] * z;
}

// Branchless 4-lane gradient dot product avoiding stack stores and store-forwarding stalls.
static CHUNKING_FORCEINLINE __m256d grad_dot4_reg(
    int h0, int h1, int h2, int h3,
    __m256d x, __m256d y, __m256d z
) {
    const __m256i vh = _mm256_cvtepi32_epi64(_mm_set_epi32(h3, h2, h1, h0));
    const __m256i mUY   = _mm256_set1_epi64x((int64_t)0x00F5000000000000ULL);
    const __m256i mUNeg = _mm256_set1_epi64x((int64_t)0x5557000000000000ULL);
    const __m256i mVZ   = _mm256_set1_epi64x((int64_t)0x0FF5000000000000ULL);
    const __m256i mVNeg = _mm256_set1_epi64x((int64_t)0x3331000000000000ULL);
    const __m256d signBit = _mm256_castsi256_pd(_mm256_set1_epi64x((int64_t)0x8000000000000000ULL));

    __m256d u = _mm256_blendv_pd(x, y, _mm256_castsi256_pd(_mm256_sllv_epi64(mUY, vh)));
    u = _mm256_xor_pd(u, _mm256_and_pd(_mm256_castsi256_pd(_mm256_sllv_epi64(mUNeg, vh)), signBit));

    __m256d v = _mm256_blendv_pd(y, z, _mm256_castsi256_pd(_mm256_sllv_epi64(mVZ, vh)));
    v = _mm256_xor_pd(v, _mm256_and_pd(_mm256_castsi256_pd(_mm256_sllv_epi64(mVNeg, vh)), signBit));

    return _mm256_add_pd(u, v);
}

struct ColumnXZ {
    double xf, xf1, zf, zf1, u, w;
    int32_t p0, p1, iz0, iz1;
};

static CHUNKING_FORCEINLINE ColumnXZ prepare_column_xz(
    const uint8_t* perm, double xCoord, double zCoord, double xo, double zo
) {
    ColumnXZ c;
    double dx = wrap_coord(xCoord) + xo;
    double dz = wrap_coord(zCoord) + zo;
    int32_t ix = fast_floor(dx);
    int32_t iz = fast_floor(dz);
    c.xf = dx - (double)ix;
    c.zf = dz - (double)iz;
    c.xf1 = c.xf - 1.0;
    c.zf1 = c.zf - 1.0;
    c.u = smoothstep_d(c.xf);
    c.w = smoothstep_d(c.zf);
    int32_t ix0 = ix & 255;
    c.p0 = perm[ix0];
    c.p1 = perm[ix0 + 1];
    c.iz0 = iz & 255;
    c.iz1 = (iz + 1) & 255;
    return c;
}

struct YSlabCoeffs {
    double lc0, ls0, lc1, ls1;
    __m256d vlc0, vls0, vlc1, vls1;
};

// Precomputes linear Y coefficients (base + slope * yf) for a uniform Y lattice slab.
static CHUNKING_FORCEINLINE YSlabCoeffs compute_y_slab(
    const ColumnXZ& xz, const uint8_t* perm, int32_t iy
) {
    int32_t iy0 = iy & 255;
    int32_t k = perm[xz.p0 + iy0];
    int32_t l = perm[xz.p0 + iy0 + 1];
    int32_t m = perm[xz.p1 + iy0];
    int32_t n = perm[xz.p1 + iy0 + 1];

    int h000 = perm[k + xz.iz0] & 15, h100 = perm[m + xz.iz0] & 15;
    int h010 = perm[l + xz.iz0] & 15, h110 = perm[n + xz.iz0] & 15;
    int h001 = perm[k + xz.iz1] & 15, h101 = perm[m + xz.iz1] & 15;
    int h011 = perm[l + xz.iz1] & 15, h111 = perm[n + xz.iz1] & 15;

    double b000 = GX[h000] * xz.xf  + GZ[h000] * xz.zf,  s000 = GY[h000];
    double b100 = GX[h100] * xz.xf1 + GZ[h100] * xz.zf,  s100 = GY[h100];
    double s010 = GY[h010], b010 = GX[h010] * xz.xf  + GZ[h010] * xz.zf  - s010;
    double s110 = GY[h110], b110 = GX[h110] * xz.xf1 + GZ[h110] * xz.zf  - s110;
    double b001 = GX[h001] * xz.xf  + GZ[h001] * xz.zf1, s001 = GY[h001];
    double b101 = GX[h101] * xz.xf1 + GZ[h101] * xz.zf1, s101 = GY[h101];
    double s011 = GY[h011], b011 = GX[h011] * xz.xf  + GZ[h011] * xz.zf1 - s011;
    double s111 = GY[h111], b111 = GX[h111] * xz.xf1 + GZ[h111] * xz.zf1 - s111;

    YSlabCoeffs out;
    out.lc0 = lerp_d(xz.w, lerp_d(xz.u, b000, b100), lerp_d(xz.u, b001, b101));
    out.ls0 = lerp_d(xz.w, lerp_d(xz.u, s000, s100), lerp_d(xz.u, s001, s101));
    out.lc1 = lerp_d(xz.w, lerp_d(xz.u, b010, b110), lerp_d(xz.u, b011, b111));
    out.ls1 = lerp_d(xz.w, lerp_d(xz.u, s010, s110), lerp_d(xz.u, s011, s111));
    out.vlc0 = _mm256_set1_pd(out.lc0);
    out.vls0 = _mm256_set1_pd(out.ls0);
    out.vlc1 = _mm256_set1_pd(out.lc1);
    out.vls1 = _mm256_set1_pd(out.ls1);
    return out;
}

static CHUNKING_FORCEINLINE __m256d eval_8corners_avx2(
    const uint8_t* perm,
    int32_t k0, int32_t k1, int32_t k2, int32_t k3,
    int32_t l0, int32_t l1, int32_t l2, int32_t l3,
    int32_t m0, int32_t m1, int32_t m2, int32_t m3,
    int32_t n0, int32_t n1, int32_t n2, int32_t n3,
    int32_t iz0_0, int32_t iz0_1, int32_t iz0_2, int32_t iz0_3,
    int32_t iz1_0, int32_t iz1_1, int32_t iz1_2, int32_t iz1_3,
    __m256d xf, __m256d xf1, __m256d yf, __m256d yf1, __m256d zf, __m256d zf1,
    __m256d u, __m256d v, __m256d w
) {
    __m256d d000 = grad_dot4_reg(perm[k0 + iz0_0] & 15, perm[k1 + iz0_1] & 15, perm[k2 + iz0_2] & 15, perm[k3 + iz0_3] & 15, xf,  yf,  zf);
    __m256d d100 = grad_dot4_reg(perm[m0 + iz0_0] & 15, perm[m1 + iz0_1] & 15, perm[m2 + iz0_2] & 15, perm[m3 + iz0_3] & 15, xf1, yf,  zf);
    __m256d d010 = grad_dot4_reg(perm[l0 + iz0_0] & 15, perm[l1 + iz0_1] & 15, perm[l2 + iz0_2] & 15, perm[l3 + iz0_3] & 15, xf,  yf1, zf);
    __m256d d110 = grad_dot4_reg(perm[n0 + iz0_0] & 15, perm[n1 + iz0_1] & 15, perm[n2 + iz0_2] & 15, perm[n3 + iz0_3] & 15, xf1, yf1, zf);
    __m256d d001 = grad_dot4_reg(perm[k0 + iz1_0] & 15, perm[k1 + iz1_1] & 15, perm[k2 + iz1_2] & 15, perm[k3 + iz1_3] & 15, xf,  yf,  zf1);
    __m256d d101 = grad_dot4_reg(perm[m0 + iz1_0] & 15, perm[m1 + iz1_1] & 15, perm[m2 + iz1_2] & 15, perm[m3 + iz1_3] & 15, xf1, yf,  zf1);
    __m256d d011 = grad_dot4_reg(perm[l0 + iz1_0] & 15, perm[l1 + iz1_1] & 15, perm[l2 + iz1_2] & 15, perm[l3 + iz1_3] & 15, xf,  yf1, zf1);
    __m256d d111 = grad_dot4_reg(perm[n0 + iz1_0] & 15, perm[n1 + iz1_1] & 15, perm[n2 + iz1_2] & 15, perm[n3 + iz1_3] & 15, xf1, yf1, zf1);

    __m256d x00 = lerp4(u, d000, d100);
    __m256d x10 = lerp4(u, d010, d110);
    __m256d x01 = lerp4(u, d001, d101);
    __m256d x11 = lerp4(u, d011, d111);
    return lerp4(w, lerp4(v, x00, x10), lerp4(v, x01, x11));
}

static CHUNKING_FORCEINLINE double eval_8corners_scalar(
    const uint8_t* perm, int32_t k, int32_t l, int32_t m, int32_t n,
    int32_t iz0, int32_t iz1,
    double xf, double xf1, double yf, double yf1, double zf, double zf1,
    double u, double v, double w
) {
    double x00 = lerp_d(u, grad_dot(perm[k + iz0] & 15, xf, yf, zf),  grad_dot(perm[m + iz0] & 15, xf1, yf, zf));
    double x10 = lerp_d(u, grad_dot(perm[l + iz0] & 15, xf, yf1, zf), grad_dot(perm[n + iz0] & 15, xf1, yf1, zf));
    double x01 = lerp_d(u, grad_dot(perm[k + iz1] & 15, xf, yf, zf1), grad_dot(perm[m + iz1] & 15, xf1, yf, zf1));
    double x11 = lerp_d(u, grad_dot(perm[l + iz1] & 15, xf, yf1, zf1), grad_dot(perm[n + iz1] & 15, xf1, yf1, zf1));
    return lerp_d(w, lerp_d(v, x00, x10), lerp_d(v, x01, x11));
}

// ---- 3. Normal Perlin noise ----

CHUNKING_EXPORT void chunking_avx2_normal_col(
    const OctaveDesc* octs,
    int32_t numOctaves,
    double xFixed,
    const int32_t* by,
    double yScale,
    double zFixed,
    double* out,
    int32_t count
) {
    if (count <= 0) return;
    for (int32_t i = 0; i < count; i++) out[i] = 0.0;
    if (numOctaves <= 0) return;

    for (int32_t o = 0; o < numOctaves; o++) {
        const OctaveDesc* oct = octs + o;
        const double factor = oct->factor;
        const double scale = oct->scale;
        const double yo = oct->yo;
        const uint8_t* perm = oct->perm;

        ColumnXZ xz = prepare_column_xz(perm, xFixed * factor, zFixed * factor, oct->xo, oct->zo);
        double yFactor = yScale * factor;

        if (yFactor == 0.0) {
            int32_t iy = fast_floor(yo);
            double yf = yo - (double)iy;
            int32_t iy0 = iy & 255;
            double delta = eval_8corners_scalar(
                perm, perm[xz.p0 + iy0], perm[xz.p0 + iy0 + 1], perm[xz.p1 + iy0], perm[xz.p1 + iy0 + 1],
                xz.iz0, xz.iz1, xz.xf, xz.xf1, yf, yf - 1.0, xz.zf, xz.zf1, xz.u, smoothstep_d(yf), xz.w) * scale;
            for (int32_t i = 0; i < count; i++) out[i] += delta;
            continue;
        }

        __m256d vu = _mm256_set1_pd(xz.u), vw = _mm256_set1_pd(xz.w);
        __m256d vyFactor = _mm256_set1_pd(yFactor), vyo = _mm256_set1_pd(yo), vscale = _mm256_set1_pd(scale);
        __m256d vone = _mm256_set1_pd(1.0);
        __m256d vxf = _mm256_set1_pd(xz.xf), vxf1 = _mm256_set1_pd(xz.xf1);
        __m256d vzf = _mm256_set1_pd(xz.zf), vzf1 = _mm256_set1_pd(xz.zf1);

        int32_t prevIy = INT32_MIN;
        YSlabCoeffs slab{};

        int32_t i = 0;
        for (; i + 3 < count; i += 4) {
            __m128i by4 = _mm_loadu_si128((const __m128i*)(by + i));
            __m256d dy = _mm256_add_pd(wrap_coord4(_mm256_mul_pd(_mm256_cvtepi32_pd(by4), vyFactor)), vyo);
            __m256d vyFloor = _mm256_floor_pd(dy);
            __m256d vyf = _mm256_sub_pd(dy, vyFloor);
            __m128i iy4 = _mm256_cvttpd_epi32(vyFloor);

            int32_t iy0 = _mm_cvtsi128_si32(iy4);
            int32_t iy1 = _mm_extract_epi32(iy4, 1);
            int32_t iy2 = _mm_extract_epi32(iy4, 2);
            int32_t iy3 = _mm_extract_epi32(iy4, 3);

            __m256d vv = smoothstep4(vyf);
            __m256d val;
            if (iy1 == iy0 && iy2 == iy0 && iy3 == iy0) {
                if (iy0 != prevIy) {
                    prevIy = iy0;
                    slab = compute_y_slab(xz, perm, iy0);
                }
                __m256d y0 = _mm256_add_pd(slab.vlc0, _mm256_mul_pd(slab.vls0, vyf));
                __m256d y1 = _mm256_add_pd(slab.vlc1, _mm256_mul_pd(slab.vls1, vyf));
                val = _mm256_mul_pd(lerp4(vv, y0, y1), vscale);
            } else {
                __m256d vyf1 = _mm256_sub_pd(vyf, vone);
                int32_t a0 = iy0 & 255, a1 = iy1 & 255, a2 = iy2 & 255, a3 = iy3 & 255;
                __m256d res = eval_8corners_avx2(
                    perm,
                    perm[xz.p0 + a0], perm[xz.p0 + a1], perm[xz.p0 + a2], perm[xz.p0 + a3],
                    perm[xz.p0 + a0 + 1], perm[xz.p0 + a1 + 1], perm[xz.p0 + a2 + 1], perm[xz.p0 + a3 + 1],
                    perm[xz.p1 + a0], perm[xz.p1 + a1], perm[xz.p1 + a2], perm[xz.p1 + a3],
                    perm[xz.p1 + a0 + 1], perm[xz.p1 + a1 + 1], perm[xz.p1 + a2 + 1], perm[xz.p1 + a3 + 1],
                    xz.iz0, xz.iz0, xz.iz0, xz.iz0, xz.iz1, xz.iz1, xz.iz1, xz.iz1,
                    vxf, vxf1, vyf, vyf1, vzf, vzf1, vu, vv, vw);
                val = _mm256_mul_pd(res, vscale);
            }
            _mm256_storeu_pd(out + i, _mm256_add_pd(_mm256_loadu_pd(out + i), val));
        }

        for (; i < count; i++) {
            double dy = wrap_coord((double)by[i] * yFactor) + yo;
            int32_t iy = fast_floor(dy);
            double yf = dy - (double)iy;
            if (iy != prevIy) {
                prevIy = iy;
                slab = compute_y_slab(xz, perm, iy);
            }
            out[i] += lerp_d(smoothstep_d(yf), slab.lc0 + slab.ls0 * yf, slab.lc1 + slab.ls1 * yf) * scale;
        }
    }
}

CHUNKING_EXPORT void chunking_avx2_normal_batch3d_int(
    const OctaveDesc* octs,
    int32_t numOctaves,
    const int32_t* bx,
    const int32_t* by,
    const int32_t* bz,
    double xzScale,
    double yScale,
    double* out,
    int32_t count
) {
    if (count <= 0) return;
    for (int32_t i = 0; i < count; i++) out[i] = 0.0;
    if (numOctaves <= 0) return;

    const __m256d vone = _mm256_set1_pd(1.0);
    const __m256d vxzScale = _mm256_set1_pd(xzScale);
    const __m256d vyScale = _mm256_set1_pd(yScale);

    for (int32_t o = 0; o < numOctaves; o++) {
        const OctaveDesc* oct = octs + o;
        const double factor = oct->factor;
        const double scale = oct->scale;
        const double xo = oct->xo, yo = oct->yo, zo = oct->zo;
        const uint8_t* perm = oct->perm;

        const __m256d vxFactor = _mm256_mul_pd(vxzScale, _mm256_set1_pd(factor));
        const __m256d vyFactor = _mm256_mul_pd(vyScale, _mm256_set1_pd(factor));
        const __m256d vxo = _mm256_set1_pd(xo), vyo = _mm256_set1_pd(yo), vzo = _mm256_set1_pd(zo);
        const __m256d vscale = _mm256_set1_pd(scale);
        const double xFactorS = xzScale * factor;
        const double yFactorS = yScale * factor;

        int32_t i = 0;
        for (; i + 3 < count; i += 4) {
            __m256d bxD = _mm256_cvtepi32_pd(_mm_loadu_si128((const __m128i*)(bx + i)));
            __m256d byD = _mm256_cvtepi32_pd(_mm_loadu_si128((const __m128i*)(by + i)));
            __m256d bzD = _mm256_cvtepi32_pd(_mm_loadu_si128((const __m128i*)(bz + i)));

            __m256d dx = _mm256_add_pd(wrap_coord4(_mm256_mul_pd(bxD, vxFactor)), vxo);
            __m256d dy = _mm256_add_pd(wrap_coord4(_mm256_mul_pd(byD, vyFactor)), vyo);
            __m256d dz = _mm256_add_pd(wrap_coord4(_mm256_mul_pd(bzD, vxFactor)), vzo);

            __m256d flX = _mm256_floor_pd(dx), flY = _mm256_floor_pd(dy), flZ = _mm256_floor_pd(dz);
            __m256d xf = _mm256_sub_pd(dx, flX), yf = _mm256_sub_pd(dy, flY), zf = _mm256_sub_pd(dz, flZ);
            __m256d xf1 = _mm256_sub_pd(xf, vone), yf1 = _mm256_sub_pd(yf, vone), zf1 = _mm256_sub_pd(zf, vone);

            __m128i ix4 = _mm256_cvttpd_epi32(flX), iy4 = _mm256_cvttpd_epi32(flY), iz4 = _mm256_cvttpd_epi32(flZ);
            int32_t ix0 = _mm_cvtsi128_si32(ix4) & 255, ix1 = _mm_extract_epi32(ix4, 1) & 255;
            int32_t ix2 = _mm_extract_epi32(ix4, 2) & 255, ix3 = _mm_extract_epi32(ix4, 3) & 255;
            int32_t iy0 = _mm_cvtsi128_si32(iy4) & 255, iy1 = _mm_extract_epi32(iy4, 1) & 255;
            int32_t iy2 = _mm_extract_epi32(iy4, 2) & 255, iy3 = _mm_extract_epi32(iy4, 3) & 255;
            int32_t iz0_0 = _mm_cvtsi128_si32(iz4) & 255,    iz1_0 = (iz0_0 + 1) & 255;
            int32_t iz0_1 = _mm_extract_epi32(iz4, 1) & 255, iz1_1 = (iz0_1 + 1) & 255;
            int32_t iz0_2 = _mm_extract_epi32(iz4, 2) & 255, iz1_2 = (iz0_2 + 1) & 255;
            int32_t iz0_3 = _mm_extract_epi32(iz4, 3) & 255, iz1_3 = (iz0_3 + 1) & 255;

            int32_t p0_0 = perm[ix0], p1_0 = perm[ix0 + 1];
            int32_t p0_1 = perm[ix1], p1_1 = perm[ix1 + 1];
            int32_t p0_2 = perm[ix2], p1_2 = perm[ix2 + 1];
            int32_t p0_3 = perm[ix3], p1_3 = perm[ix3 + 1];

            __m256d res = eval_8corners_avx2(
                perm,
                perm[p0_0 + iy0], perm[p0_1 + iy1], perm[p0_2 + iy2], perm[p0_3 + iy3],
                perm[p0_0 + iy0 + 1], perm[p0_1 + iy1 + 1], perm[p0_2 + iy2 + 1], perm[p0_3 + iy3 + 1],
                perm[p1_0 + iy0], perm[p1_1 + iy1], perm[p1_2 + iy2], perm[p1_3 + iy3],
                perm[p1_0 + iy0 + 1], perm[p1_1 + iy1 + 1], perm[p1_2 + iy2 + 1], perm[p1_3 + iy3 + 1],
                iz0_0, iz0_1, iz0_2, iz0_3, iz1_0, iz1_1, iz1_2, iz1_3,
                xf, xf1, yf, yf1, zf, zf1, smoothstep4(xf), smoothstep4(yf), smoothstep4(zf));
            _mm256_storeu_pd(out + i, _mm256_add_pd(_mm256_loadu_pd(out + i), _mm256_mul_pd(res, vscale)));
        }

        for (; i < count; i++) {
            double dx = wrap_coord((double)bx[i] * xFactorS) + xo;
            double dy = wrap_coord((double)by[i] * yFactorS) + yo;
            double dz = wrap_coord((double)bz[i] * xFactorS) + zo;
            int32_t ix = fast_floor(dx), iy = fast_floor(dy), iz = fast_floor(dz);
            double xf = dx - (double)ix, yf = dy - (double)iy, zf = dz - (double)iz;
            int32_t ix0 = ix & 255, iy0 = iy & 255, iz0 = iz & 255;
            int32_t p0 = perm[ix0], p1 = perm[ix0 + 1];
            out[i] += eval_8corners_scalar(
                perm, perm[p0 + iy0], perm[p0 + iy0 + 1], perm[p1 + iy0], perm[p1 + iy0 + 1],
                iz0, (iz0 + 1) & 255, xf, xf - 1.0, yf, yf - 1.0, zf, zf - 1.0,
                smoothstep_d(xf), smoothstep_d(yf), smoothstep_d(zf)) * scale;
        }
    }
}

// ---- 4. Blended noise ----

static CHUNKING_FORCEINLINE void eval_blended_octave_avx2(
    const BlendedOctaveDesc* oct,
    double xBase,
    double zBase,
    const int32_t* by,
    double* acc,
    int32_t start,
    int32_t end,
    int32_t maskMode, // 0=all, 1=skip blend>=1.0, 2=skip blend<=0.0
    const double* blendArr
) {
    const double yFactor = oct->yFactor;
    const double smearYScale = oct->smearYScale;
    const double invSmearYScale = oct->invSmearYScale;
    const double invWeight = oct->invWeight;
    const double yo = oct->yo;
    const uint8_t* perm = oct->perm;

    ColumnXZ xz = prepare_column_xz(perm, xBase * oct->xzScale, zBase * oct->xzScale, oct->xo, oct->zo);
    const bool hasSmear = (smearYScale != 0.0);

    const __m256d vu = _mm256_set1_pd(xz.u), vw = _mm256_set1_pd(xz.w);
    const __m256d vyFactor = _mm256_set1_pd(yFactor), vyo = _mm256_set1_pd(yo);
    const __m256d vInvWeight = _mm256_set1_pd(invWeight);
    const __m256d vone = _mm256_set1_pd(1.0), vzero = _mm256_setzero_pd();
    const __m256d vxf = _mm256_set1_pd(xz.xf), vxf1 = _mm256_set1_pd(xz.xf1);
    const __m256d vzf = _mm256_set1_pd(xz.zf), vzf1 = _mm256_set1_pd(xz.zf1);
    const __m256d vSmear = _mm256_set1_pd(smearYScale), vInvSmear = _mm256_set1_pd(invSmearYScale);
    const __m256d vEps = _mm256_set1_pd(SHIFT_UP_EPSILON);

    int32_t prevIy = INT32_MIN;
    YSlabCoeffs slab{};

    int32_t i = start;
    for (; i + 3 < end; i += 4) {
        if (maskMode == 1 && _mm256_movemask_pd(_mm256_cmp_pd(_mm256_loadu_pd(blendArr + i), vone, _CMP_LT_OQ)) == 0) {
            continue;
        }
        if (maskMode == 2 && _mm256_movemask_pd(_mm256_cmp_pd(_mm256_loadu_pd(blendArr + i), vzero, _CMP_GT_OQ)) == 0) {
            continue;
        }

        __m128i by4 = _mm_loadu_si128((const __m128i*)(by + i));
        __m256d yScaled = _mm256_mul_pd(_mm256_cvtepi32_pd(by4), vyFactor);
        __m256d dy = _mm256_add_pd(wrap_coord4(yScaled), vyo);
        __m256d vyFloor = _mm256_floor_pd(dy);
        __m256d vyf = _mm256_sub_pd(dy, vyFloor);

        __m256d vWeirdY = vyf;
        if (hasSmear) {
            __m256d useScaled = _mm256_and_pd(
                _mm256_cmp_pd(yScaled, vzero, _CMP_GE_OQ),
                _mm256_cmp_pd(yScaled, vyf, _CMP_LT_OQ));
            __m256d vc = _mm256_blendv_pd(vyf, yScaled, useScaled);
            __m256d vq = _mm256_floor_pd(_mm256_add_pd(_mm256_mul_pd(vc, vInvSmear), vEps));
            vWeirdY = _mm256_sub_pd(vyf, _mm256_mul_pd(vq, vSmear));
        }

        __m128i iy4 = _mm256_cvttpd_epi32(vyFloor);
        int32_t iy0 = _mm_cvtsi128_si32(iy4);
        int32_t iy1 = _mm_extract_epi32(iy4, 1);
        int32_t iy2 = _mm_extract_epi32(iy4, 2);
        int32_t iy3 = _mm_extract_epi32(iy4, 3);

        __m256d vv = smoothstep4(vyf);
        __m256d val;
        if (iy1 == iy0 && iy2 == iy0 && iy3 == iy0) {
            if (iy0 != prevIy) {
                prevIy = iy0;
                slab = compute_y_slab(xz, perm, iy0);
            }
            __m256d y0 = _mm256_add_pd(slab.vlc0, _mm256_mul_pd(slab.vls0, vWeirdY));
            __m256d y1 = _mm256_add_pd(slab.vlc1, _mm256_mul_pd(slab.vls1, vWeirdY));
            val = _mm256_mul_pd(lerp4(vv, y0, y1), vInvWeight);
        } else {
            __m256d vwy1 = _mm256_sub_pd(vWeirdY, vone);
            int32_t a0 = iy0 & 255, a1 = iy1 & 255, a2 = iy2 & 255, a3 = iy3 & 255;
            __m256d res = eval_8corners_avx2(
                perm,
                perm[xz.p0 + a0], perm[xz.p0 + a1], perm[xz.p0 + a2], perm[xz.p0 + a3],
                perm[xz.p0 + a0 + 1], perm[xz.p0 + a1 + 1], perm[xz.p0 + a2 + 1], perm[xz.p0 + a3 + 1],
                perm[xz.p1 + a0], perm[xz.p1 + a1], perm[xz.p1 + a2], perm[xz.p1 + a3],
                perm[xz.p1 + a0 + 1], perm[xz.p1 + a1 + 1], perm[xz.p1 + a2 + 1], perm[xz.p1 + a3 + 1],
                xz.iz0, xz.iz0, xz.iz0, xz.iz0, xz.iz1, xz.iz1, xz.iz1, xz.iz1,
                vxf, vxf1, vWeirdY, vwy1, vzf, vzf1, vu, vv, vw);
            val = _mm256_mul_pd(res, vInvWeight);
        }
        _mm256_storeu_pd(acc + i, _mm256_add_pd(_mm256_loadu_pd(acc + i), val));
    }

    for (; i < end; i++) {
        if (maskMode == 1 && blendArr[i] >= 1.0) continue;
        if (maskMode == 2 && blendArr[i] <= 0.0) continue;

        double yScaled = (double)by[i] * yFactor;
        double dy = wrap_coord(yScaled) + yo;
        int32_t iy = fast_floor(dy);
        double yf = dy - (double)iy;
        double weirdY = yf;
        if (hasSmear) {
            double c = (yScaled >= 0.0 && yScaled < yf) ? yScaled : yf;
            weirdY = yf - (double)fast_floor(c * invSmearYScale + SHIFT_UP_EPSILON) * smearYScale;
        }
        if (iy != prevIy) {
            prevIy = iy;
            slab = compute_y_slab(xz, perm, iy);
        }
        acc[i] += lerp_d(smoothstep_d(yf), slab.lc0 + slab.ls0 * weirdY, slab.lc1 + slab.ls1 * weirdY) * invWeight;
    }
}

CHUNKING_EXPORT void chunking_avx2_blended_col(
    const BlendedOctaveDesc* octs,
    int32_t numMain,
    int32_t numMin,
    int32_t numMax,
    double dx,
    double dz,
    double fx,
    double fz,
    const int32_t* by,
    int32_t startIdx,
    double* out,
    int32_t totalCount
) {
    if (startIdx < 0) startIdx = 0;
    int32_t rem = totalCount - startIdx;
    if (rem <= 0) return;

    const BlendedOctaveDesc* mainOcts = octs;
    const BlendedOctaveDesc* minOcts = octs + numMain;
    const BlendedOctaveDesc* maxOcts = minOcts + numMin;

    static constexpr int32_t TILE_SIZE = 128;
    alignas(32) double mainBuf[TILE_SIZE];
    alignas(32) double minBuf[TILE_SIZE];
    alignas(32) double maxBuf[TILE_SIZE];

    int32_t baseOff = startIdx;
    while (rem > 0) {
        const int32_t tile = (rem > TILE_SIZE) ? TILE_SIZE : rem;
        const int32_t* byTile = by + baseOff;
        double* outTile = out + baseOff;

        for (int32_t i = 0; i < tile; i++) mainBuf[i] = 0.0;
        for (int32_t o = 0; o < numMain; o++) {
            eval_blended_octave_avx2(mainOcts + o, fx, fz, byTile, mainBuf, 0, tile, 0, nullptr);
        }

        int32_t minFirst = tile, minLast = 0;
        int32_t maxFirst = tile, maxLast = 0;
        for (int32_t i = 0; i < tile; i++) {
            double b = (mainBuf[i] / 10.0 + 1.0) / 2.0;
            mainBuf[i] = b;
            if (b < 1.0) { if (i < minFirst) minFirst = i; minLast = i + 1; }
            if (b > 0.0) { if (i < maxFirst) maxFirst = i; maxLast = i + 1; }
        }

        const bool anyMin = (minFirst < minLast);
        const bool anyMax = (maxFirst < maxLast);

        if (anyMin) {
            for (int32_t i = minFirst; i < minLast; i++) minBuf[i] = 0.0;
            for (int32_t o = 0; o < numMin; o++) {
                eval_blended_octave_avx2(minOcts + o, dx, dz, byTile, minBuf, minFirst, minLast, anyMax ? 1 : 0, mainBuf);
            }
        }
        if (anyMax) {
            for (int32_t i = maxFirst; i < maxLast; i++) maxBuf[i] = 0.0;
            for (int32_t o = 0; o < numMax; o++) {
                eval_blended_octave_avx2(maxOcts + o, dx, dz, byTile, maxBuf, maxFirst, maxLast, anyMin ? 2 : 0, mainBuf);
            }
        }

        if (!anyMin) {
            for (int32_t i = 0; i < tile; i++) outTile[i] = (maxBuf[i] / 512.0) / 128.0;
        } else if (!anyMax) {
            for (int32_t i = 0; i < tile; i++) outTile[i] = (minBuf[i] / 512.0) / 128.0;
        } else {
            for (int32_t i = 0; i < tile; i++) {
                double b = mainBuf[i];
                double res;
                if (b <= 0.0) res = minBuf[i] / 512.0;
                else if (b >= 1.0) res = maxBuf[i] / 512.0;
                else {
                    double lo = minBuf[i] / 512.0;
                    res = lo + b * (maxBuf[i] / 512.0 - lo);
                }
                outTile[i] = res / 128.0;
            }
        }

        baseOff += tile;
        rem -= tile;
    }
}

// ---- 5. Biome R-tree search ----

// Must match NativeAvx2.packFlatRTreeNodes layout (64 bytes, 1 L1 cache line).
struct alignas(64) FlatRTreeNode {
    int32_t lo[6];
    int64_t offsetSq;
    int32_t hi[6];
    int32_t childStart;
    int32_t childEnd;
};

static_assert(sizeof(FlatRTreeNode) == 64, "FlatRTreeNode must be 64 bytes");

static CHUNKING_FORCEINLINE int64_t dist7_node_avx2(
    const FlatRTreeNode* node, __m256i vtarget, __m256i vmask
) {
    __m256i vlo = _mm256_loadu_si256((const __m256i*)node->lo);
    __m256i vhi = _mm256_loadu_si256((const __m256i*)node->hi);

    __m256i dHi = _mm256_sub_epi32(_mm256_max_epi32(vtarget, vhi), vhi);
    __m256i dLo = _mm256_sub_epi32(vlo, _mm256_min_epi32(vtarget, vlo));
    __m256i d = _mm256_and_si256(_mm256_or_si256(dHi, dLo), vmask);

    __m256i sqEven = _mm256_mul_epu32(d, d);
    __m256i dOdd = _mm256_srli_epi64(d, 32);
    __m256i sqOdd = _mm256_mul_epu32(dOdd, dOdd);
    __m256i sum4 = _mm256_add_epi64(sqEven, sqOdd);

    __m128i sum2 = _mm_add_epi64(_mm256_castsi256_si128(sum4), _mm256_extracti128_si256(sum4, 1));
    int64_t dist6 = _mm_cvtsi128_si64(_mm_add_epi64(sum2, _mm_unpackhi_epi64(sum2, sum2)));
    return dist6 + node->offsetSq;
}

static CHUNKING_FORCEINLINE bool is_depth_band_winner(
    const int64_t* bMins, const int64_t* bMaxs, int32_t len,
    int64_t pMin, int64_t pMax, int64_t d, int64_t dist
) {
    if (bMins == nullptr || bMaxs == nullptr || len <= 0) return false;
    for (int32_t i = 0; i < len; i++) {
        if (bMins[i] == pMin && bMaxs[i] == pMax) {
            if (i > 0) {
                int64_t gapLo = d - bMaxs[i - 1];
                if (gapLo <= 0 || (gapLo <= 3000000000LL && gapLo * gapLo <= dist)) return false;
            }
            if (i + 1 < len) {
                int64_t gapHi = bMins[i + 1] - d;
                if (gapHi <= 0 || (gapHi <= 3000000000LL && gapHi * gapHi <= dist)) return false;
            }
            return true;
        }
    }
    return false;
}

static constexpr int32_t RTREE_DFS_STACK_CAP = 256;

static CHUNKING_FORCEINLINE int32_t search_tree_avx2(
    const FlatRTreeNode* nodes, int32_t rootStart, int32_t rootEnd,
    int32_t* dfsCur, int32_t* dfsEnd, int64_t minDist, __m256i vtarget, __m256i vmask6
) {
    int32_t bestIdx = -1;
    int32_t sp = 0;
    dfsCur[0] = rootStart;
    dfsEnd[0] = rootEnd;
    while (sp >= 0) {
        int32_t idx = dfsCur[sp];
        if (idx >= dfsEnd[sp]) {
            sp--;
            continue;
        }
        dfsCur[sp] = idx + 1;
        const FlatRTreeNode* node = nodes + idx;
        int64_t cd = dist7_node_avx2(node, vtarget, vmask6);
        if (minDist > cd) {
            int32_t cs = node->childStart;
            if (cs < 0) {
                if (cd == 0) return idx;
                minDist = cd;
                bestIdx = idx;
            } else {
                if (sp + 1 >= RTREE_DFS_STACK_CAP) return INT32_MIN;
                sp++;
                dfsCur[sp] = cs;
                dfsEnd[sp] = node->childEnd;
            }
        }
    }
    return bestIdx;
}

CHUNKING_EXPORT int32_t chunking_avx2_rtree_search_column(
    const FlatRTreeNode* nodes,
    int32_t nodeCount,
    int32_t seedLeafIdx,
    int32_t allDepthNonNeg,
    int32_t disjointDepthBands,
    const int64_t* depthBandMins,
    const int64_t* depthBandMaxs,
    int32_t bandCount,
    int32_t t0, int32_t t1, int32_t t2, int32_t t3, int32_t t5,
    const int32_t* depths,
    int32_t* outLeafIdx,
    int32_t count
) {
    if (count <= 0) return -1;
    if (nodeCount <= 0) {
        for (int32_t yi = 0; yi < count; yi++) outLeafIdx[yi] = -1;
        return -1;
    }
    if (nodes[0].childStart < 0) {
        for (int32_t yi = 0; yi < count; yi++) outLeafIdx[yi] = 0;
        return 0;
    }

    const __m256i vmask6 = _mm256_setr_epi32(-1, -1, -1, -1, -1, -1, 0, 0);
    const __m256i vmask5 = _mm256_setr_epi32(-1, -1, -1, -1,  0, -1, 0, 0);
    const __m256i vtargetBase = _mm256_setr_epi32(t0, t1, t2, t3, 0, t5, 0, 0);

    int32_t curLeafIdx = (seedLeafIdx >= 0 && seedLeafIdx < nodeCount && nodes[seedLeafIdx].childStart < 0)
        ? seedLeafIdx : -1;
    int64_t curLeafDMin = 0, curLeafDMax = 0, curLeafBaseDist = 0;
    if (curLeafIdx >= 0) {
        const FlatRTreeNode* leaf = nodes + curLeafIdx;
        curLeafDMin = (int64_t)leaf->lo[4];
        curLeafDMax = (int64_t)leaf->hi[4];
        curLeafBaseDist = dist7_node_avx2(leaf, vtargetBase, vmask5);
    }

    const int32_t rootStart = nodes[0].childStart;
    const int32_t rootEnd = nodes[0].childEnd;
    if (rootStart < 0 || rootEnd > nodeCount || rootStart >= rootEnd) return INT32_MIN;

    int32_t dfsCur[RTREE_DFS_STACK_CAP];
    int32_t dfsEnd[RTREE_DFS_STACK_CAP];
    int64_t prevD = INT64_MIN;
    bool hasPrev = false;

    for (int32_t yi = 0; yi < count; yi++) {
        const int32_t t4 = depths[yi];
        const int64_t d = (int64_t)t4;
        if (hasPrev && d == prevD) {
            outLeafIdx[yi] = curLeafIdx;
            continue;
        }

        int64_t minDist = INT64_MAX;
        if (curLeafIdx >= 0) {
            if (hasPrev && allDepthNonNeg && prevD <= 0 && d <= prevD && curLeafDMin == 0) {
                prevD = d;
                outLeafIdx[yi] = curLeafIdx;
                continue;
            }
            int64_t dd = d > curLeafDMax ? (d - curLeafDMax) : (d < curLeafDMin ? (curLeafDMin - d) : 0);
            int64_t dist = curLeafBaseDist + dd * dd;
            if (dist == 0) {
                prevD = d;
                hasPrev = true;
                outLeafIdx[yi] = curLeafIdx;
                continue;
            }
            if (hasPrev && disjointDepthBands
                    && prevD >= curLeafDMin && prevD <= curLeafDMax
                    && d >= curLeafDMin && d <= curLeafDMax
                    && is_depth_band_winner(
                        depthBandMins, depthBandMaxs, bandCount, curLeafDMin, curLeafDMax, d, dist)) {
                prevD = d;
                outLeafIdx[yi] = curLeafIdx;
                continue;
            }
            minDist = dist;
        }

        const __m256i vtarget = _mm256_setr_epi32(t0, t1, t2, t3, t4, t5, 0, 0);
        int32_t winIdx = search_tree_avx2(nodes, rootStart, rootEnd, dfsCur, dfsEnd, minDist, vtarget, vmask6);
        if (winIdx == INT32_MIN) return INT32_MIN;
        if (winIdx >= 0) {
            curLeafIdx = winIdx;
            const FlatRTreeNode* leaf = nodes + winIdx;
            curLeafDMin = (int64_t)leaf->lo[4];
            curLeafDMax = (int64_t)leaf->hi[4];
            curLeafBaseDist = dist7_node_avx2(leaf, vtargetBase, vmask5);
        }

        hasPrev = true;
        prevD = d;
        outLeafIdx[yi] = curLeafIdx;
    }

    return curLeafIdx;
}

} // extern "C"
