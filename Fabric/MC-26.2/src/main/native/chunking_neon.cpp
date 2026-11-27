#include <limits.h>
#include <stddef.h>
#include <stdint.h>

#if defined(__clang__)
#pragma STDC FP_CONTRACT OFF
#endif

#define CHUNKING_FORCEINLINE __attribute__((always_inline)) inline
#define CHUNKING_EXPORT __attribute__((visibility("default")))

extern "C" {

// ---- 1. CPU feature check ----

// ARM64 always has NEON; compiled without libc/libm/libstdc++ dependencies.
CHUNKING_EXPORT int32_t chunking_avx2_cpu_supported() {
    return 1;
}

// ---- 2. Shared ABI structs & math helpers ----

struct alignas(32) OctaveDesc {
    double factor;
    double scale;
    double xo;
    double yo;
    double zo;
    double _pad;
    uint8_t perm[512];
};

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

struct alignas(32) FlatRTreeNode {
    int32_t lo[6];
    int64_t offsetSq;
    int32_t hi[6];
    int32_t childStart;
    int32_t childEnd;
};

static_assert(sizeof(OctaveDesc) == 576, "OctaveDesc must be 576 bytes");
static_assert(sizeof(BlendedOctaveDesc) == 576, "BlendedOctaveDesc must be 576 bytes");
static_assert(sizeof(FlatRTreeNode) == 64, "FlatRTreeNode must be 64 bytes");
static_assert(offsetof(FlatRTreeNode, offsetSq) == 24, "FlatRTreeNode::offsetSq must be at offset 24");
static_assert(offsetof(FlatRTreeNode, childStart) == 56, "FlatRTreeNode::childStart must be at offset 56");

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
    return v - __builtin_floor(v / 3.3554432E7 + 0.5) * 3.3554432E7;
}

static CHUNKING_FORCEINLINE double smoothstep_d(double t) {
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

static CHUNKING_FORCEINLINE double lerp_d(double t, double a, double b) {
    return a + t * (b - a);
}

static CHUNKING_FORCEINLINE double grad_dot(int h, double x, double y, double z) {
    const int g = h & 15;
    return GX[g] * x + GY[g] * y + GZ[g] * z;
}

static CHUNKING_FORCEINLINE double eval_8corners(
    const uint8_t* p, int32_t p00, int32_t p01, int32_t p10, int32_t p11, int32_t z0,
    double xf, double wy, double zf, double u, double v, double w
) {
    const double g000 = grad_dot(p[p00 + z0],     xf,       wy,       zf);
    const double g100 = grad_dot(p[p10 + z0],     xf - 1.0, wy,       zf);
    const double g010 = grad_dot(p[p01 + z0],     xf,       wy - 1.0, zf);
    const double g110 = grad_dot(p[p11 + z0],     xf - 1.0, wy - 1.0, zf);
    const double g001 = grad_dot(p[p00 + z0 + 1], xf,       wy,       zf - 1.0);
    const double g101 = grad_dot(p[p10 + z0 + 1], xf - 1.0, wy,       zf - 1.0);
    const double g011 = grad_dot(p[p01 + z0 + 1], xf,       wy - 1.0, zf - 1.0);
    const double g111 = grad_dot(p[p11 + z0 + 1], xf - 1.0, wy - 1.0, zf - 1.0);

    const double x00 = lerp_d(u, g000, g100);
    const double x10 = lerp_d(u, g010, g110);
    const double x01 = lerp_d(u, g001, g101);
    const double x11 = lerp_d(u, g011, g111);
    return lerp_d(w, lerp_d(v, x00, x10), lerp_d(v, x01, x11));
}

struct ColumnXZ {
    double xf, zf, u, w;
    int32_t pX0, pX1, z0;
};

static CHUNKING_FORCEINLINE ColumnXZ prepare_column_xz(
    const uint8_t* p, double xCoord, double zCoord, double xo, double zo
) {
    ColumnXZ c;
    const double gx = wrap_coord(xCoord) + xo;
    const double gz = wrap_coord(zCoord) + zo;
    const int32_t xi = fast_floor(gx);
    const int32_t zi = fast_floor(gz);
    c.xf = gx - (double)xi;
    c.zf = gz - (double)zi;
    c.u = smoothstep_d(c.xf);
    c.w = smoothstep_d(c.zf);
    c.pX0 = p[xi & 255];
    c.pX1 = p[(xi & 255) + 1];
    c.z0 = zi & 255;
    return c;
}

// ---- 3. Normal Perlin noise ----

CHUNKING_EXPORT void chunking_avx2_normal_col(
    const OctaveDesc* octs, int32_t numOctaves,
    double xFixed, const int32_t* by, double yScale, double zFixed,
    double* out, int32_t count
) {
    for (int32_t i = 0; i < count; i++) out[i] = 0.0;
    for (int32_t o = 0; o < numOctaves; o++) {
        const OctaveDesc& od = octs[o];
        const double factor = od.factor;
        const double scale = od.scale;
        const uint8_t* p = od.perm;
        const ColumnXZ xz = prepare_column_xz(p, xFixed * factor, zFixed * factor, od.xo, od.zo);
        const double yStep = yScale * factor;
        const double yo = od.yo;

        for (int32_t i = 0; i < count; i++) {
            const double gy = wrap_coord((double)by[i] * yStep) + yo;
            const int32_t yi = fast_floor(gy);
            const double yf = gy - (double)yi;
            const int y0 = yi & 255;
            out[i] += eval_8corners(
                p, p[xz.pX0 + y0], p[xz.pX0 + y0 + 1], p[xz.pX1 + y0], p[xz.pX1 + y0 + 1],
                xz.z0, xz.xf, yf, xz.zf, xz.u, smoothstep_d(yf), xz.w) * scale;
        }
    }
}

CHUNKING_EXPORT void chunking_avx2_normal_batch3d_int(
    const OctaveDesc* octs, int32_t numOctaves,
    const int32_t* bx, const int32_t* by, const int32_t* bz,
    double xzScale, double yScale, double* out, int32_t count
) {
    for (int32_t i = 0; i < count; i++) out[i] = 0.0;
    for (int32_t o = 0; o < numOctaves; o++) {
        const OctaveDesc& od = octs[o];
        const double xzMul = xzScale * od.factor;
        const double yMul = yScale * od.factor;
        const double scale = od.scale;
        const double xo = od.xo, yo = od.yo, zo = od.zo;
        const uint8_t* p = od.perm;

        for (int32_t i = 0; i < count; i++) {
            const double gx = wrap_coord((double)bx[i] * xzMul) + xo;
            const double gy = wrap_coord((double)by[i] * yMul) + yo;
            const double gz = wrap_coord((double)bz[i] * xzMul) + zo;
            const int32_t xi = fast_floor(gx), yi = fast_floor(gy), zi = fast_floor(gz);
            const double xf = gx - (double)xi, yf = gy - (double)yi, zf = gz - (double)zi;
            const int x0 = xi & 255, y0 = yi & 255;
            const int pX0 = p[x0], pX1 = p[x0 + 1];
            out[i] += eval_8corners(
                p, p[pX0 + y0], p[pX0 + y0 + 1], p[pX1 + y0], p[pX1 + y0 + 1],
                zi & 255, xf, yf, zf, smoothstep_d(xf), smoothstep_d(yf), smoothstep_d(zf)) * scale;
        }
    }
}

// ---- 4. Blended noise ----

static CHUNKING_FORCEINLINE void eval_blended_octave_scalar(
    const BlendedOctaveDesc* od, double xIn, double zIn, const int32_t* by,
    double* acc, int32_t start, int32_t end, int32_t maskMode, const double* mainBuf
) {
    const double yFactor = od->yFactor;
    const double smearYScale = od->smearYScale;
    const double invSmearYScale = od->invSmearYScale;
    const double invWeight = od->invWeight;
    const double yo = od->yo;
    const uint8_t* p = od->perm;
    const ColumnXZ xz = prepare_column_xz(p, xIn * od->xzScale, zIn * od->xzScale, od->xo, od->zo);
    const bool hasSmear = (smearYScale != 0.0);

    for (int32_t i = start; i < end; i++) {
        if (maskMode == 1 && !(mainBuf[i] < 1.0)) continue;
        if (maskMode == 2 && !(mainBuf[i] > 0.0)) continue;
        const double yScaled = (double)by[i] * yFactor;
        const double gy = wrap_coord(yScaled) + yo;
        const int32_t yi = fast_floor(gy);
        const double yf = gy - (double)yi;
        double weirdY = yf;
        if (hasSmear) {
            const double c = (yScaled >= 0.0 && yScaled < yf) ? yScaled : yf;
            weirdY = yf - __builtin_floor(c * invSmearYScale + SHIFT_UP_EPSILON) * smearYScale;
        }
        const int y0 = yi & 255;
        acc[i] += eval_8corners(
            p, p[xz.pX0 + y0], p[xz.pX0 + y0 + 1], p[xz.pX1 + y0], p[xz.pX1 + y0 + 1],
            xz.z0, xz.xf, weirdY, xz.zf, xz.u, smoothstep_d(yf), xz.w) * invWeight;
    }
}

CHUNKING_EXPORT void chunking_avx2_blended_col(
    const BlendedOctaveDesc* octs,
    int32_t numMain, int32_t numMin, int32_t numMax,
    double dx, double dz, double fx, double fz,
    const int32_t* by, int32_t startIdx, double* out, int32_t totalCount
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
            eval_blended_octave_scalar(mainOcts + o, fx, fz, byTile, mainBuf, 0, tile, 0, nullptr);
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
                eval_blended_octave_scalar(minOcts + o, dx, dz, byTile, minBuf, minFirst, minLast, anyMax ? 1 : 0, mainBuf);
            }
        }
        if (anyMax) {
            for (int32_t i = maxFirst; i < maxLast; i++) maxBuf[i] = 0.0;
            for (int32_t o = 0; o < numMax; o++) {
                eval_blended_octave_scalar(maxOcts + o, dx, dz, byTile, maxBuf, maxFirst, maxLast, anyMin ? 2 : 0, mainBuf);
            }
        }

        if (!anyMin && !anyMax) {
            for (int32_t i = 0; i < tile; i++) outTile[i] = mainBuf[i];
        } else if (!anyMin) {
            for (int32_t i = 0; i < tile; i++) outTile[i] = (maxBuf[i] / 512.0) / 128.0;
        } else if (!anyMax) {
            for (int32_t i = 0; i < tile; i++) outTile[i] = (minBuf[i] / 512.0) / 128.0;
        } else {
            for (int32_t i = 0; i < tile; i++) {
                const double b = mainBuf[i];
                if (!(b == b)) {
                    outTile[i] = b;
                    continue;
                }
                double clamped;
                if (b <= 0.0) clamped = minBuf[i] / 512.0;
                else if (b >= 1.0) clamped = maxBuf[i] / 512.0;
                else {
                    const double lo = minBuf[i] / 512.0;
                    clamped = lo + b * (maxBuf[i] / 512.0 - lo);
                }
                outTile[i] = clamped / 128.0;
            }
        }

        baseOff += tile;
        rem -= tile;
    }
}

// ---- 5. Biome R-tree search ----

static CHUNKING_FORCEINLINE int64_t axis_dist(int64_t t, int32_t lo, int32_t hi) {
    const int64_t l = (int64_t)lo, h = (int64_t)hi;
    if (t > h) return t - h;
    if (t < l) return l - t;
    return 0;
}

static CHUNKING_FORCEINLINE int64_t dist7_node_scalar(
    const FlatRTreeNode* node, int64_t t0, int64_t t1, int64_t t2, int64_t t3, int64_t t4, int64_t t5
) {
    const int64_t d0 = axis_dist(t0, node->lo[0], node->hi[0]);
    const int64_t d1 = axis_dist(t1, node->lo[1], node->hi[1]);
    const int64_t d2 = axis_dist(t2, node->lo[2], node->hi[2]);
    const int64_t d3 = axis_dist(t3, node->lo[3], node->hi[3]);
    const int64_t d4 = axis_dist(t4, node->lo[4], node->hi[4]);
    const int64_t d5 = axis_dist(t5, node->lo[5], node->hi[5]);
    return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4 + d5 * d5 + node->offsetSq;
}

static CHUNKING_FORCEINLINE int64_t dist5_node_scalar(
    const FlatRTreeNode* node, int64_t t0, int64_t t1, int64_t t2, int64_t t3, int64_t t5
) {
    const int64_t d0 = axis_dist(t0, node->lo[0], node->hi[0]);
    const int64_t d1 = axis_dist(t1, node->lo[1], node->hi[1]);
    const int64_t d2 = axis_dist(t2, node->lo[2], node->hi[2]);
    const int64_t d3 = axis_dist(t3, node->lo[3], node->hi[3]);
    const int64_t d5 = axis_dist(t5, node->lo[5], node->hi[5]);
    return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d5 * d5 + node->offsetSq;
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

    int32_t curLeafIdx = (seedLeafIdx >= 0 && seedLeafIdx < nodeCount && nodes[seedLeafIdx].childStart < 0)
        ? seedLeafIdx : -1;
    int64_t curLeafDMin = 0, curLeafDMax = 0, curLeafBaseDist = 0;
    if (curLeafIdx >= 0) {
        const FlatRTreeNode* leaf = nodes + curLeafIdx;
        curLeafDMin = (int64_t)leaf->lo[4];
        curLeafDMax = (int64_t)leaf->hi[4];
        curLeafBaseDist = dist5_node_scalar(leaf, t0, t1, t2, t3, t5);
    }

    const int32_t rootStart = nodes[0].childStart;
    const int32_t rootEnd = nodes[0].childEnd;
    if (rootStart < 0 || rootEnd > nodeCount || rootStart >= rootEnd) return INT32_MIN;

    int32_t dfsCur[RTREE_DFS_STACK_CAP];
    int32_t dfsEnd[RTREE_DFS_STACK_CAP];
    int64_t prevD = INT64_MIN;
    bool hasPrev = false;

    for (int32_t yi = 0; yi < count; yi++) {
        const int64_t d = (int64_t)depths[yi];
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
            const int64_t dd = d > curLeafDMax ? (d - curLeafDMax) : (d < curLeafDMin ? (curLeafDMin - d) : 0);
            const int64_t dist = curLeafBaseDist + dd * dd;
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
            const int64_t cd = dist7_node_scalar(node, t0, t1, t2, t3, d, t5);
            if (minDist > cd) {
                const int32_t cs = node->childStart;
                if (cs < 0) {
                    bestIdx = idx;
                    if (cd == 0) break;
                    minDist = cd;
                } else {
                    if (sp + 1 >= RTREE_DFS_STACK_CAP) return INT32_MIN;
                    sp++;
                    dfsCur[sp] = cs;
                    dfsEnd[sp] = node->childEnd;
                }
            }
        }

        if (bestIdx >= 0) {
            curLeafIdx = bestIdx;
            const FlatRTreeNode* leaf = nodes + bestIdx;
            curLeafDMin = (int64_t)leaf->lo[4];
            curLeafDMax = (int64_t)leaf->hi[4];
            curLeafBaseDist = dist5_node_scalar(leaf, t0, t1, t2, t3, t5);
        }
        prevD = d;
        hasPrev = true;
        outLeafIdx[yi] = curLeafIdx;
    }
    return curLeafIdx;
}

} // extern "C"
