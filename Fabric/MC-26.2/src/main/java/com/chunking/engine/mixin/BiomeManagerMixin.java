package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.PalettedContainerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * Fast-paths uniform sections, 2x2x2 uniform quart cubes, and thread-local quart caches
 * in BiomeManager.getBiome while keeping Voronoi tie-breaking bit-exact.
 */
@Mixin(BiomeManager.class)
public abstract class BiomeManagerMixin {
    @Shadow
    @Final
    private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    @Shadow
    @Final
    private long biomeZoomSeed;

    @Shadow
    private static double getFiddledDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
        throw new AssertionError();
    }

    /**
     * Strict lower bounds on {@code (offset + fiddle)^2} for {@code |fiddle| <= 0.45} and
     * {@code offset = (i & 3) * 0.25} (index 0..3) or {@code offset - 1.0}, with {@code 1e-4}
     * safety margin against IEEE-754 rounding.
     */
    @Unique
    private static final double[] CHUNKING$MIN_SQ_0 = { 0.0D, 0.0D, 0.0024D, 0.0899D };

    @Unique
    private static final double[] CHUNKING$MIN_SQ_1 = { 0.3024D, 0.0899D, 0.0024D, 0.0D };

    @Unique
    private ChunkAccess chunking$centerChunk;

    @Unique
    private int chunking$centerChunkX;

    @Unique
    private int chunking$centerChunkZ;

    @Unique
    private boolean chunking$centerHasBiomes;

    @Unique
    private boolean chunking$cacheableSource;

    @Unique
    private static final ThreadLocal<QuartCache> CHUNKING$QUART_CACHE_TL =
            ThreadLocal.withInitial(QuartCache::new);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$captureSource(BiomeManager.NoiseBiomeSource source, long seed, CallbackInfo ci) {
        this.chunking$centerChunkX = Integer.MIN_VALUE;
        this.chunking$centerChunkZ = Integer.MIN_VALUE;
        this.chunking$cacheableSource = false;
        if (source == null) {
            return;
        }
        if (source.getClass() == WorldGenRegion.class) {
            WorldGenRegion region = (WorldGenRegion) source;
            ChunkPos cp = region.getCenter();
            ChunkAccess center = region.getChunk(cp.x(), cp.z());
            ChunkStatus status = center != null ? center.getPersistedStatus() : null;
            boolean hasBiomes = status != null && status.isOrAfter(ChunkStatus.BIOMES);
            this.chunking$centerChunk = center;
            this.chunking$centerChunkX = cp.x();
            this.chunking$centerChunkZ = cp.z();
            this.chunking$centerHasBiomes = hasBiomes;
            this.chunking$cacheableSource = hasBiomes;
        } else if (source instanceof ProtoChunk proto && !(proto instanceof ImposterProtoChunk)) {
            ChunkPos cp = proto.getPos();
            ChunkStatus status = proto.getPersistedStatus();
            boolean hasBiomes = status != null && status.isOrAfter(ChunkStatus.BIOMES);
            this.chunking$centerChunk = proto;
            this.chunking$centerChunkX = cp.x();
            this.chunking$centerChunkZ = cp.z();
            this.chunking$centerHasBiomes = hasBiomes;
            this.chunking$cacheableSource = hasBiomes;
        } else {
            Class<?> cls = source.getClass();
            if (cls == ImposterProtoChunk.class || cls == LevelChunk.class) {
                ChunkAccess chunk = cls == ImposterProtoChunk.class
                        ? ((ImposterProtoChunk) source).getWrapped()
                        : (ChunkAccess) source;
                ChunkPos cp = chunk.getPos();
                this.chunking$centerChunk = chunk;
                this.chunking$centerChunkX = cp.x();
                this.chunking$centerChunkZ = cp.z();
                this.chunking$centerHasBiomes = true;
                this.chunking$cacheableSource = false;
            }
        }
    }

    /**
     * @author Chunking
     * @reason Fast uniform-section, 2x2x2 uniform quart-cube, pruned Voronoi, and thread-local 64-entry quart + last-chunk cache.
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public Holder<Biome> getBiome(BlockPos pos) {
        int bx = pos.getX();
        int by = pos.getY();
        int bz = pos.getZ();
        int i = bx - 2;
        int j = by - 2;
        int k = bz - 2;
        int qx = i >> 2;
        int qy = j >> 2;
        int qz = k >> 2;

        BiomeManager.NoiseBiomeSource src = this.noiseBiomeSource;
        if (ModConfig.SIMD_NOISE && src != null) {
            if (this.chunking$cacheableSource) {
                QuartCache cache = CHUNKING$QUART_CACHE_TL.get();
                ChunkAccess center = this.chunking$centerChunk;
                boolean cubeHit = false;
                if (cache.prepare(src, this.biomeZoomSeed)) {
                    if (cache.lastBx == bx && cache.lastBy == by && cache.lastBz == bz && cache.lastBiome != null) {
                        return cache.lastBiome;
                    }
                    if (qx == cache.lastCubeQx && qy == cache.lastCubeQy && qz == cache.lastCubeQz) {
                        if (cache.lastCubeUniform != null) {
                            return cache.lastCubeUniform;
                        }
                        cubeHit = true;
                    }
                }

                if (!cubeHit) {
                    boolean interiorXZ = (qx & 3) < 3 && (qz & 3) < 3;
                    if (interiorXZ) {
                        int cx = qx >> 2;
                        int cz = qz >> 2;
                        if (center != null && this.chunking$centerHasBiomes
                                && cx == this.chunking$centerChunkX
                                && cz == this.chunking$centerChunkZ) {
                            Holder<Biome> single = chunking$tryUniformSectionBiome(center, qy);
                            if (single != null) {
                                cache.rememberCube(qx, qy, qz, single);
                                return single;
                            }
                        } else if (cache.lastChunk != null && cx == cache.lastCx && cz == cache.lastCz) {
                            Holder<Biome> single = chunking$tryUniformSectionBiome(cache.lastChunk, qy);
                            if (single != null) {
                                cache.rememberCube(qx, qy, qz, single);
                                return single;
                            }
                        }
                    }

                    Holder<Biome> uniform = cache.checkUniformCube(
                            src,
                            center,
                            this.chunking$centerChunkX,
                            this.chunking$centerChunkZ,
                            this.chunking$centerHasBiomes,
                            qx,
                            qy,
                            qz
                    );
                    if (uniform != null) {
                        return uniform;
                    }
                }

                int ix = i & 3;
                int iy = j & 3;
                int iz = k & 3;
                int bestIdx = closestCorner(
                        this.biomeZoomSeed, qx, qy, qz, ix, iy, iz, ix * 0.25D, iy * 0.25D, iz * 0.25D);
                int rx = (bestIdx & 4) == 0 ? qx : qx + 1;
                int ry = (bestIdx & 2) == 0 ? qy : qy + 1;
                int rz = (bestIdx & 1) == 0 ? qz : qz + 1;
                Holder<Biome> result = cache.getOrFetch(
                        src,
                        center,
                        this.chunking$centerChunkX,
                        this.chunking$centerChunkZ,
                        this.chunking$centerHasBiomes,
                        rx,
                        ry,
                        rz
                );
                cache.rememberBlock(bx, by, bz, result);
                return result;
            }

            boolean interiorXZ = (qx & 3) < 3 && (qz & 3) < 3;
            if (interiorXZ && src instanceof Level level) {
                ChunkAccess liveChunk = level.getChunkSource().getChunkNow(qx >> 2, qz >> 2);
                if (liveChunk != null) {
                    Holder<Biome> single = chunking$tryUniformSectionBiome(liveChunk, qy);
                    if (single != null) {
                        return single;
                    }
                }
            }
        }

        int ix = i & 3;
        int iy = j & 3;
        int iz = k & 3;
        int bestIdx = closestCorner(
                this.biomeZoomSeed, qx, qy, qz, ix, iy, iz, ix * 0.25D, iy * 0.25D, iz * 0.25D);
        int rx = (bestIdx & 4) == 0 ? qx : qx + 1;
        int ry = (bestIdx & 2) == 0 ? qy : qy + 1;
        int rz = (bestIdx & 1) == 0 ? qz : qz + 1;
        return src.getNoiseBiome(rx, ry, rz);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static Holder<Biome> chunking$tryUniformSectionBiome(ChunkAccess chunk, int qy) {
        int secIdx = chunk.getSectionIndexFromSectionY(qy >> 2);
        LevelChunkSection[] sections = chunk.getSections();
        if (secIdx < 0 || secIdx >= sections.length) {
            return null;
        }
        PalettedContainerRO<Holder<Biome>> biomes = sections[secIdx].getBiomes();
        if (!(biomes instanceof PalettedContainerAccess<?> palAcc)) {
            return null;
        }
        Holder<Biome> single = ((PalettedContainerAccess<Holder<Biome>>) palAcc).chunking$getSingleUniformValue();
        if (single == null) {
            return null;
        }
        if ((qy & 3) < 3) {
            return single;
        }
        int nextSec = secIdx + 1;
        if (nextSec < sections.length
                && sections[nextSec].getBiomes() instanceof PalettedContainerAccess<?> nextAcc
                && nextAcc.chunking$getSingleUniformValue() == single) {
            return single;
        }
        return null;
    }

    @Unique
    private static int closestCorner(
            long seed,
            int qx,
            int qy,
            int qz,
            int ix,
            int iy,
            int iz,
            double dx,
            double dy,
            double dz
    ) {
        int qx1 = qx + 1;
        int qy1 = qy + 1;
        int qz1 = qz + 1;
        double dx1 = dx - 1.0D;
        double dy1 = dy - 1.0D;
        double dz1 = dz - 1.0D;

        double minX0 = CHUNKING$MIN_SQ_0[ix];
        double minX1 = CHUNKING$MIN_SQ_1[ix];
        double minY0 = CHUNKING$MIN_SQ_0[iy];
        double minY1 = CHUNKING$MIN_SQ_1[iy];
        double minZ0 = CHUNKING$MIN_SQ_0[iz];
        double minZ1 = CHUNKING$MIN_SQ_1[iz];

        // Evaluate the geometrically nearest corner first to establish a tight distance bound.
        int c0 = ((ix >= 2) ? 4 : 0) | ((iy >= 2) ? 2 : 0) | ((iz >= 2) ? 1 : 0);
        int bestIdx = c0;
        double bestDist = getFiddledDistance(
                seed,
                (c0 & 4) == 0 ? qx : qx1,
                (c0 & 2) == 0 ? qy : qy1,
                (c0 & 1) == 0 ? qz : qz1,
                (c0 & 4) == 0 ? dx : dx1,
                (c0 & 2) == 0 ? dy : dy1,
                (c0 & 1) == 0 ? dz : dz1
        );

        for (int c = 0; c < 8; c++) {
            if (c == c0) {
                continue;
            }
            boolean flagX = (c & 4) == 0;
            boolean flagY = (c & 2) == 0;
            boolean flagZ = (c & 1) == 0;
            double minSq = (flagX ? minX0 : minX1) + (flagY ? minY0 : minY1) + (flagZ ? minZ0 : minZ1);
            if (minSq <= bestDist) {
                double dist = getFiddledDistance(
                        seed,
                        flagX ? qx : qx1,
                        flagY ? qy : qy1,
                        flagZ ? qz : qz1,
                        flagX ? dx : dx1,
                        flagY ? dy : dy1,
                        flagZ ? dz : dz1
                );
                if (dist < bestDist || (dist == bestDist && c < bestIdx)) {
                    bestIdx = c;
                    bestDist = dist;
                }
            }
        }
        return bestIdx;
    }

    @Unique
    private static final class QuartCache {
        private static final int CACHE_SIZE = 64;

        WeakReference<BiomeManager.NoiseBiomeSource> sourceRef;
        long seed;

        final long[] keys = new long[CACHE_SIZE];
        @SuppressWarnings("unchecked")
        final Holder<Biome>[] values = (Holder<Biome>[]) new Holder[CACHE_SIZE];

        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        ChunkAccess lastChunk;

        int lastBx = Integer.MIN_VALUE;
        int lastBy = Integer.MIN_VALUE;
        int lastBz = Integer.MIN_VALUE;
        Holder<Biome> lastBiome;

        int lastCubeQx = Integer.MIN_VALUE;
        int lastCubeQy = Integer.MIN_VALUE;
        int lastCubeQz = Integer.MIN_VALUE;
        Holder<Biome> lastCubeUniform;

        boolean prepare(BiomeManager.NoiseBiomeSource src, long zoomSeed) {
            BiomeManager.NoiseBiomeSource prev = this.sourceRef != null ? this.sourceRef.get() : null;
            if (prev == src && this.seed == zoomSeed) {
                return true;
            }
            if (prev != src) {
                this.sourceRef = new WeakReference<>(src);
            }
            this.seed = zoomSeed;
            Arrays.fill(this.values, null);
            this.lastChunk = null;
            this.lastCx = Integer.MIN_VALUE;
            this.lastCz = Integer.MIN_VALUE;
            this.lastBiome = null;
            this.lastBx = Integer.MIN_VALUE;
            this.lastCubeQx = Integer.MIN_VALUE;
            this.lastCubeQy = Integer.MIN_VALUE;
            this.lastCubeQz = Integer.MIN_VALUE;
            this.lastCubeUniform = null;
            return false;
        }

        void rememberBlock(int bx, int by, int bz, Holder<Biome> biome) {
            this.lastBx = bx;
            this.lastBy = by;
            this.lastBz = bz;
            this.lastBiome = biome;
        }

        void rememberCube(int qx, int qy, int qz, Holder<Biome> uniformBiome) {
            this.lastCubeQx = qx;
            this.lastCubeQy = qy;
            this.lastCubeQz = qz;
            this.lastCubeUniform = uniformBiome;
        }

        Holder<Biome> checkUniformCube(
                BiomeManager.NoiseBiomeSource src,
                ChunkAccess centerChunk,
                int centerCx,
                int centerCz,
                boolean centerHasBiomes,
                int qx,
                int qy,
                int qz
        ) {
            Holder<Biome> b0 = getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx, qy, qz);
            if (b0 != null
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx + 1, qy, qz) == b0
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx, qy, qz + 1) == b0
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx + 1, qy, qz + 1) == b0
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx, qy + 1, qz) == b0
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx + 1, qy + 1, qz) == b0
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx, qy + 1, qz + 1) == b0
                    && getOrFetch(src, centerChunk, centerCx, centerCz, centerHasBiomes, qx + 1, qy + 1, qz + 1) == b0) {
                rememberCube(qx, qy, qz, b0);
                return b0;
            }
            rememberCube(qx, qy, qz, null);
            return null;
        }

        private static long packQuart(int rx, int ry, int rz) {
            return (((long) rx & 0x3FFFFFFL) << 38)
                    | (((long) rz & 0x3FFFFFFL) << 12)
                    | ((long) ry & 0xFFFL);
        }

        Holder<Biome> getOrFetch(
                BiomeManager.NoiseBiomeSource src,
                ChunkAccess centerChunk,
                int centerCx,
                int centerCz,
                boolean centerHasBiomes,
                int nrx,
                int nry,
                int nrz
        ) {
            int slot = (nrx & 3) | ((nrz & 3) << 2) | ((nry & 3) << 4);
            long key = packQuart(nrx, nry, nrz);
            Holder<Biome> cached = this.values[slot];
            if (cached != null && this.keys[slot] == key) {
                return cached;
            }

            Holder<Biome> fetched;
            int cx = nrx >> 2;
            int cz = nrz >> 2;
            if (centerChunk != null && centerHasBiomes && cx == centerCx && cz == centerCz) {
                fetched = centerChunk.getNoiseBiome(nrx, nry, nrz);
            } else if (centerHasBiomes && src instanceof WorldGenRegion region) {
                ChunkAccess chunk = this.lastChunk;
                if (chunk == null || this.lastCx != cx || this.lastCz != cz) {
                    chunk = region.getChunk(cx, cz, ChunkStatus.BIOMES, false);
                    if (chunk != null) {
                        this.lastCx = cx;
                        this.lastCz = cz;
                        this.lastChunk = chunk;
                    }
                }
                fetched = chunk != null ? chunk.getNoiseBiome(nrx, nry, nrz) : src.getNoiseBiome(nrx, nry, nrz);
            } else {
                fetched = src.getNoiseBiome(nrx, nry, nrz);
            }

            this.keys[slot] = key;
            this.values[slot] = fetched;
            return fetched;
        }
    }
}
