package com.chunking.engine.mixin;

import com.chunking.engine.access.PalettedContainerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;
import java.util.List;

/**
 * Optimizes OreFeature placement with per-chunk heightmap fast paths, reusable ThreadLocal
 * scratch buffers, local section caching, and deterministic RuleTest identity caching.
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
    @Unique
    private static final ThreadLocal<OreScratch> CHUNKING$SCRATCH_TL =
            ThreadLocal.withInitial(OreScratch::new);

    @Unique
    private static boolean chunking$isDeterministicRuleTest(RuleTest test) {
        if (test == null) {
            return false;
        }
        Class<?> cls = test.getClass();
        return cls == TagMatchTest.class
                || cls == BlockMatchTest.class
                || cls == BlockStateMatchTest.class
                || cls == AlwaysTrueTest.class;
    }

    @Unique
    private static boolean chunking$matchesAnyTarget(
            BlockState state,
            RandomSource random,
            int targetCount,
            OreConfiguration.TargetBlockState t0,
            OreConfiguration.TargetBlockState t1,
            OreConfiguration.TargetBlockState[] targetsArr
    ) {
        if (targetCount == 1) {
            return t0.target.test(state, random);
        }
        if (targetCount == 2) {
            return t0.target.test(state, random) || t1.target.test(state, random);
        }
        for (int t = 0; t < targetCount; t++) {
            if (targetsArr[t].target.test(state, random)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static final class OreScratch {
        long[] bits = new long[32];
        double[] coords = new double[256];
        final BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        final OreSectionCache sectionCache = new OreSectionCache();
        boolean inUse;
    }

    @Unique
    private static final class OreSectionCache {
        private long[] keys = new long[16];
        private LevelChunkSection[] sections = new LevelChunkSection[16];
        @SuppressWarnings("unchecked")
        private PalettedContainer<BlockState>[] statesArr = new PalettedContainer[16];
        private BlockState[] uniformStates = new BlockState[16];
        private boolean[] uniformNonAirArr = new boolean[16];
        private byte[] matchFlags = new byte[16]; // 0 = untested, 1 = may match / mixed, 2 = uniform non-matching
        private int count;
        LevelChunkSection lastSection;
        PalettedContainer<BlockState> lastStates;
        BlockState lastUniform;
        boolean lastUniformNonAir;
        private byte lastMatchFlag;
        private int lastIdx = -1;
        private long lastSectionKey = Long.MIN_VALUE;

        @SuppressWarnings("unchecked")
        private int ensureSectionIndex(WorldGenLevel level, int sx, int sy, int sz, int secIdx) {
            long key = SectionPos.asLong(sx, sy, sz);
            if (this.lastIdx >= 0 && this.lastSectionKey == key) {
                return this.lastIdx;
            }
            long[] ks = this.keys;
            for (int i = 0, n = this.count; i < n; i++) {
                if (ks[i] == key) {
                    this.lastSection = this.sections[i];
                    this.lastStates = this.statesArr[i];
                    this.lastUniform = this.uniformStates[i];
                    this.lastUniformNonAir = this.uniformNonAirArr[i];
                    this.lastMatchFlag = this.matchFlags[i];
                    this.lastIdx = i;
                    this.lastSectionKey = key;
                    return i;
                }
            }
            LevelChunkSection sec = level.getChunk(sx, sz).getSection(secIdx);
            PalettedContainer<BlockState> states = null;
            BlockState uniform = null;
            boolean uniformNonAir = false;
            if (sec != null) {
                sec.acquire();
                states = sec.getStates();
                if ((Object) states instanceof PalettedContainerAccess<?> palAcc) {
                    uniform = ((PalettedContainerAccess<BlockState>) palAcc).chunking$getSingleUniformValue();
                }
                uniformNonAir = uniform != null && !uniform.isAir();
            }
            LevelChunkSection[] secs = this.sections;
            PalettedContainer<BlockState>[] stArr = this.statesArr;
            BlockState[] uniArr = this.uniformStates;
            boolean[] uniNonAir = this.uniformNonAirArr;
            byte[] flags = this.matchFlags;
            if (this.count == ks.length) {
                int newCap = ks.length << 1;
                ks = Arrays.copyOf(ks, newCap);
                secs = Arrays.copyOf(secs, newCap);
                stArr = Arrays.copyOf(stArr, newCap);
                uniArr = Arrays.copyOf(uniArr, newCap);
                uniNonAir = Arrays.copyOf(uniNonAir, newCap);
                flags = Arrays.copyOf(flags, newCap);
                this.keys = ks;
                this.sections = secs;
                this.statesArr = stArr;
                this.uniformStates = uniArr;
                this.uniformNonAirArr = uniNonAir;
                this.matchFlags = flags;
            }
            int idx = this.count++;
            ks[idx] = key;
            secs[idx] = sec;
            stArr[idx] = states;
            uniArr[idx] = uniform;
            uniNonAir[idx] = uniformNonAir;
            flags[idx] = 0;
            this.lastSection = sec;
            this.lastStates = states;
            this.lastUniform = uniform;
            this.lastUniformNonAir = uniformNonAir;
            this.lastMatchFlag = 0;
            this.lastIdx = idx;
            this.lastSectionKey = key;
            return idx;
        }

        boolean loadMatchingSection(
                WorldGenLevel level,
                int sx,
                int sy,
                int sz,
                int minSecY,
                int sectionsCount,
                RandomSource random,
                int targetCount,
                OreConfiguration.TargetBlockState t0,
                OreConfiguration.TargetBlockState t1,
                OreConfiguration.TargetBlockState[] targetsArr,
                boolean deterministicRules
        ) {
            int secIdx = sy - minSecY;
            if (secIdx < 0 || secIdx >= sectionsCount) {
                this.lastSection = null;
                this.lastStates = null;
                this.lastUniform = null;
                this.lastUniformNonAir = false;
                return false;
            }
            int idx = ensureSectionIndex(level, sx, sy, sz, secIdx);
            if (idx < 0 || this.lastSection == null) {
                return false;
            }
            byte flag = this.lastMatchFlag;
            if (flag == 2) {
                return false;
            }
            if (flag == 1) {
                return true;
            }
            BlockState uniform = this.lastUniform;
            if (deterministicRules && uniform != null
                    && !chunking$matchesAnyTarget(uniform, random, targetCount, t0, t1, targetsArr)) {
                this.matchFlags[idx] = 2;
                this.lastMatchFlag = 2;
                return false;
            }
            this.matchFlags[idx] = 1;
            this.lastMatchFlag = 1;
            return true;
        }

        void clearUniformNonAir(LevelChunkSection section) {
            if (this.lastSection == section) {
                this.lastUniformNonAir = false;
                if (this.lastIdx >= 0) {
                    this.uniformNonAirArr[this.lastIdx] = false;
                }
                return;
            }
            LevelChunkSection[] secs = this.sections;
            for (int i = 0, n = this.count; i < n; i++) {
                if (secs[i] == section) {
                    this.uniformNonAirArr[i] = false;
                    return;
                }
            }
        }

        BlockState getBlockState(WorldGenLevel level, int bx, int by, int bz, int minSecY, int sectionsCount) {
            int sy = by >> 4;
            int secIdx = sy - minSecY;
            if (secIdx < 0 || secIdx >= sectionsCount) {
                return Blocks.AIR.defaultBlockState();
            }
            int idx = ensureSectionIndex(level, bx >> 4, sy, bz >> 4, secIdx);
            if (idx < 0 || this.lastSection == null) {
                return Blocks.AIR.defaultBlockState();
            }
            if (this.lastUniformNonAir || (this.lastMatchFlag == 2 && this.lastUniform != null)) {
                return this.lastUniform;
            }
            return this.lastStates.get(bx & 15, by & 15, bz & 15);
        }

        void releaseAll() {
            LevelChunkSection[] secs = this.sections;
            PalettedContainer<BlockState>[] stArr = this.statesArr;
            BlockState[] uniArr = this.uniformStates;
            for (int i = 0, n = this.count; i < n; i++) {
                LevelChunkSection sec = secs[i];
                if (sec != null) {
                    sec.release();
                    secs[i] = null;
                }
                stArr[i] = null;
                uniArr[i] = null;
            }
            this.count = 0;
            this.lastSection = null;
            this.lastStates = null;
            this.lastUniform = null;
            this.lastUniformNonAir = false;
            this.lastMatchFlag = 0;
            this.lastIdx = -1;
            this.lastSectionKey = Long.MIN_VALUE;
        }
    }

    @Shadow
    protected static boolean shouldSkipAirCheck(RandomSource random, float discardChanceOnAirExposure) {
        throw new AssertionError();
    }

    /**
     * @author Chunking
     * @reason Eliminate per-block level.getHeight/getChunk lookups in OreFeature.place by checking OCEAN_FLOOR_WG per chunk with build-height fast paths.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<OreConfiguration> context) {
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        WorldGenLevel level = context.level();
        OreConfiguration config = context.config();
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = (float) config.size / 8.0F;
        int radius = Mth.ceil(((float) config.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double sinSpread = Math.sin(angle) * (double) spread;
        double cosSpread = Math.cos(angle) * (double) spread;
        double minX = (double) origin.getX() + sinSpread;
        double maxX = (double) origin.getX() - sinSpread;
        double minZ = (double) origin.getZ() + cosSpread;
        double maxZ = (double) origin.getZ() - cosSpread;
        double minY = (double) (origin.getY() + random.nextInt(3) - 2);
        double maxY = (double) (origin.getY() + random.nextInt(3) - 2);
        int spreadCeil = Mth.ceil(spread);
        int x = origin.getX() - spreadCeil - radius;
        int y = origin.getY() - 2 - radius;
        int z = origin.getZ() - spreadCeil - radius;
        int size = 2 * (spreadCeil + radius);
        int height = 2 * (2 + radius);

        int minBuildY = level.getMinY();
        if (y < minBuildY) {
            return this.doPlace(level, random, config, minX, maxX, minZ, maxZ, minY, maxY, x, y, z, size, height);
        }
        if (y > level.getMaxY() + 1) {
            return false;
        }

        int maxXBlock = x + size;
        int maxZBlock = z + size;
        int minChunkX = x >> 4;
        int maxChunkX = maxXBlock >> 4;
        int minChunkZ = z >> 4;
        int maxChunkZ = maxZBlock >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            int bxStart = Math.max(x, cx << 4);
            int bxEnd = Math.min(maxXBlock, (cx << 4) + 15);
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                int bzStart = Math.max(z, cz << 4);
                int bzEnd = Math.min(maxZBlock, (cz << 4) + 15);
                ChunkAccess chunk = level.getChunk(cx, cz);
                for (int bx = bxStart; bx <= bxEnd; bx++) {
                    int lx = bx & 15;
                    for (int bz = bzStart; bz <= bzEnd; bz++) {
                        if (y <= chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, bz & 15) + 1) {
                            return this.doPlace(level, random, config, minX, maxX, minZ, maxZ, minY, maxY, x, y, z, size, height);
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * @author Chunking
     * @reason Single ThreadLocal scratch, raw long[] visited bitset, local section caching, uniform-section PalettedContainer.get bypass, and 2-entry deterministic RuleTest identity cache.
     */
    @Overwrite
    protected boolean doPlace(
            WorldGenLevel level,
            RandomSource random,
            OreConfiguration config,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            double minY,
            double maxY,
            int x,
            int y,
            int z,
            int size,
            int height
    ) {
        int placedCount = 0;
        OreScratch scratch = CHUNKING$SCRATCH_TL.get();
        if (scratch.inUse) {
            scratch = new OreScratch();
        }
        scratch.inUse = true;

        OreSectionCache sectionCache = scratch.sectionCache;
        BlockPos.MutableBlockPos mutPos = scratch.mutPos;
        int sizeCount = config.size;
        int neededDoubles = sizeCount * 4;
        double[] coords = scratch.coords;
        if (coords.length < neededDoubles) {
            coords = new double[neededDoubles];
            scratch.coords = coords;
        }

        int zStride = size * height;
        int maxEstBit = (size + 1) + (height + 1) * size + (size + 1) * zStride;
        int minWords = maxEstBit > 0 ? (maxEstBit >>> 6) + 1 : 1;
        long[] bits = scratch.bits;
        if (bits.length < minWords) {
            bits = new long[Math.max(minWords, bits.length << 1)];
            scratch.bits = bits;
        }
        int maxWordUsed = -1;

        try {
            for (int i = 0; i < sizeCount; i++) {
                float progress = (float) i / (float) sizeCount;
                double px = Mth.lerp(progress, minX, maxX);
                double py = Mth.lerp(progress, minY, maxY);
                double pz = Mth.lerp(progress, minZ, maxZ);
                double scale = random.nextDouble() * (double) sizeCount / 16.0D;
                double radius = ((double) (Mth.sin((float) Math.PI * progress) + 1.0F) * scale + 1.0D) / 2.0D;
                int base = i * 4;
                coords[base] = px;
                coords[base + 1] = py;
                coords[base + 2] = pz;
                coords[base + 3] = radius;
            }

            List<OreConfiguration.TargetBlockState> targetStates = config.targetStates;
            int targetCount = targetStates.size();
            if (targetCount == 0) {
                return false;
            }

            for (int i1 = 0; i1 < sizeCount - 1; i1++) {
                int base1 = i1 * 4;
                if (coords[base1 + 3] <= 0.0D) {
                    continue;
                }
                for (int i2 = i1 + 1; i2 < sizeCount; i2++) {
                    int base2 = i2 * 4;
                    if (coords[base2 + 3] <= 0.0D) {
                        continue;
                    }
                    double dx = coords[base1] - coords[base2];
                    double dy = coords[base1 + 1] - coords[base2 + 1];
                    double dz = coords[base1 + 2] - coords[base2 + 2];
                    double dr = coords[base1 + 3] - coords[base2 + 3];
                    if (dr * dr > dx * dx + dy * dy + dz * dz) {
                        if (dr > 0.0D) {
                            coords[base2 + 3] = -1.0D;
                        } else {
                            coords[base1 + 3] = -1.0D;
                        }
                    }
                }
            }

            float discardChance = config.discardChanceOnAirExposure;
            boolean alwaysPlace = discardChance <= 0.0F;
            OreConfiguration.TargetBlockState[] targetsArr = targetCount > 2
                    ? targetStates.toArray(new OreConfiguration.TargetBlockState[targetCount])
                    : null;
            OreConfiguration.TargetBlockState t0 = targetStates.get(0);
            OreConfiguration.TargetBlockState t1 = targetCount == 2 ? targetStates.get(1) : null;
            RuleTest rt0 = t0.target;
            BlockState ts0 = t0.state;
            RuleTest rt1 = t1 != null ? t1.target : null;
            BlockState ts1 = t1 != null ? t1.state : null;

            boolean deterministicRules;
            if (targetCount == 1) {
                deterministicRules = chunking$isDeterministicRuleTest(rt0);
            } else if (targetCount == 2) {
                deterministicRules = chunking$isDeterministicRuleTest(rt0) && chunking$isDeterministicRuleTest(rt1);
            } else {
                deterministicRules = true;
                for (int t = 0; t < targetCount; t++) {
                    if (!chunking$isDeterministicRuleTest(targetsArr[t].target)) {
                        deterministicRules = false;
                        break;
                    }
                }
            }

            // 2-entry BlockState identity cache for deterministic RuleTests (null value = NO_MATCH)
            BlockState cacheKey0 = null;
            BlockState cacheVal0 = null;
            BlockState cacheKey1 = null;
            BlockState cacheVal1 = null;

            int minBuildY = level.getMinY();
            int maxBuildY = level.getMaxY();
            int minSecY = SectionPos.blockToSectionCoord(minBuildY);
            int sectionsCount = level.getSectionsCount();

            boolean isWorldGenRegion = level instanceof WorldGenRegion;
            int lastOkChunkX = Integer.MIN_VALUE;
            int lastOkChunkZ = Integer.MIN_VALUE;
            int lastFailChunkX = Integer.MIN_VALUE;
            int lastFailChunkZ = Integer.MIN_VALUE;
            int centerChunkX = Integer.MIN_VALUE;
            int centerChunkZ = Integer.MIN_VALUE;
            if (isWorldGenRegion) {
                ChunkPos center = ((WorldGenRegion) level).getCenter();
                centerChunkX = center.x();
                centerChunkZ = center.z();
                lastOkChunkX = centerChunkX;
                lastOkChunkZ = centerChunkZ;
            }

            // Local section cache carried across (x, y, z) and across spheres
            int cachedSecX = Integer.MIN_VALUE;
            int cachedSecY = Integer.MIN_VALUE;
            int cachedSecZ = Integer.MIN_VALUE;
            LevelChunkSection cachedSection = null;
            PalettedContainer<BlockState> cachedStates = null;
            BlockState cachedUniform = null;
            boolean cachedUniformNonAir = false;
            boolean cachedSectionMatches = false;

            for (int i = 0; i < sizeCount; i++) {
                int base = i * 4;
                double radius = coords[base + 3];
                if (radius < 0.0D) {
                    continue;
                }
                double cx = coords[base];
                double cy = coords[base + 1];
                double cz = coords[base + 2];

                int startX = Math.max(Mth.floor(cx - radius), x);
                int startY = Math.max(Mth.floor(cy - radius), y);
                int startZ = Math.max(Mth.floor(cz - radius), z);
                int endX = Math.max(Mth.floor(cx + radius), startX);
                int endY = Math.max(Mth.floor(cy + radius), startY);
                int endZ = Math.max(Mth.floor(cz + radius), startZ);

                int loopStartY = Math.max(startY, minBuildY);
                int loopEndY = Math.min(endY, maxBuildY);
                if (loopStartY > loopEndY) {
                    continue;
                }

                int startSecX = startX >> 4;
                int endSecX = endX >> 4;
                int startSecY = loopStartY >> 4;
                int endSecY = loopEndY >> 4;
                int startSecZ = startZ >> 4;
                int endSecZ = endZ >> 4;

                boolean sphereWritable = isWorldGenRegion
                        && startSecX == lastOkChunkX && endSecX == lastOkChunkX
                        && startSecZ == lastOkChunkZ && endSecZ == lastOkChunkZ;

                LevelChunkSection singleSection = null;
                PalettedContainer<BlockState> singleStates = null;
                BlockState singleUniform = null;
                boolean singleUniformNonAir = false;
                if (isWorldGenRegion && startSecX == endSecX && startSecY == endSecY && startSecZ == endSecZ) {
                    if (!sphereWritable) {
                        if (startSecX != lastOkChunkX || startSecZ != lastOkChunkZ) {
                            if (startSecX == lastFailChunkX && startSecZ == lastFailChunkZ) {
                                continue;
                            }
                            if (startSecX != centerChunkX || startSecZ != centerChunkZ) {
                                mutPos.set(startX, loopStartY, startZ);
                                if (!level.ensureCanWrite(mutPos)) {
                                    lastFailChunkX = startSecX;
                                    lastFailChunkZ = startSecZ;
                                    continue;
                                }
                            }
                            lastOkChunkX = startSecX;
                            lastOkChunkZ = startSecZ;
                        }
                        sphereWritable = true;
                    }
                    if (startSecX != cachedSecX || startSecY != cachedSecY || startSecZ != cachedSecZ) {
                        cachedSecX = startSecX;
                        cachedSecY = startSecY;
                        cachedSecZ = startSecZ;
                        cachedSectionMatches = sectionCache.loadMatchingSection(
                                level, startSecX, startSecY, startSecZ, minSecY, sectionsCount,
                                random, targetCount, t0, t1, targetsArr, deterministicRules);
                        cachedSection = sectionCache.lastSection;
                        cachedStates = sectionCache.lastStates;
                        cachedUniform = sectionCache.lastUniform;
                        cachedUniformNonAir = sectionCache.lastUniformNonAir;
                    }
                    if (!cachedSectionMatches) {
                        continue;
                    }
                    singleSection = cachedSection;
                    singleStates = cachedStates;
                    singleUniform = cachedUniform;
                    singleUniformNonAir = cachedUniformNonAir;
                }

                for (int bx = startX; bx <= endX; bx++) {
                    double dx = ((double) bx + 0.5D - cx) / radius;
                    double dx2 = dx * dx;
                    if (dx2 >= 1.0D) {
                        continue;
                    }
                    int sx = bx >> 4;
                    int relX = bx & 15;
                    int bitBaseX = bx - x;

                    for (int by = loopStartY; by <= loopEndY; by++) {
                        double dy = ((double) by + 0.5D - cy) / radius;
                        double dxy2 = dx2 + dy * dy;
                        if (dxy2 >= 1.0D) {
                            continue;
                        }
                        int sy = by >> 4;
                        int relY = by & 15;
                        int bitBaseXY = bitBaseX + (by - y) * size;

                        for (int bz = startZ; bz <= endZ; bz++) {
                            double dz = ((double) bz + 0.5D - cz) / radius;
                            if (dxy2 + dz * dz >= 1.0D) {
                                continue;
                            }
                            int bitIndex = bitBaseXY + (bz - z) * zStride;
                            int wordIdx = bitIndex >>> 6;
                            if (wordIdx >= bits.length) {
                                bits = Arrays.copyOf(bits, Math.max(wordIdx + 1, bits.length << 1));
                                scratch.bits = bits;
                            }
                            long bitMask = 1L << bitIndex;
                            if ((bits[wordIdx] & bitMask) != 0L) {
                                continue;
                            }
                            bits[wordIdx] |= bitMask;
                            if (wordIdx > maxWordUsed) {
                                maxWordUsed = wordIdx;
                            }

                            int sz = bz >> 4;
                            if (!sphereWritable) {
                                if (isWorldGenRegion) {
                                    if (sx != lastOkChunkX || sz != lastOkChunkZ) {
                                        if (sx == lastFailChunkX && sz == lastFailChunkZ) {
                                            bz |= 15;
                                            continue;
                                        }
                                        if (sx != centerChunkX || sz != centerChunkZ) {
                                            mutPos.set(bx, by, bz);
                                            if (!level.ensureCanWrite(mutPos)) {
                                                lastFailChunkX = sx;
                                                lastFailChunkZ = sz;
                                                bz |= 15;
                                                continue;
                                            }
                                        }
                                        lastOkChunkX = sx;
                                        lastOkChunkZ = sz;
                                    }
                                } else {
                                    mutPos.set(bx, by, bz);
                                    if (!level.ensureCanWrite(mutPos)) {
                                        continue;
                                    }
                                }
                            }

                            LevelChunkSection section;
                            PalettedContainer<BlockState> states;
                            BlockState secUniform;
                            boolean secUniformNonAir;
                            if (singleSection != null) {
                                section = singleSection;
                                states = singleStates;
                                secUniform = singleUniform;
                                secUniformNonAir = singleUniformNonAir;
                            } else {
                                if (sx != cachedSecX || sy != cachedSecY || sz != cachedSecZ) {
                                    cachedSecX = sx;
                                    cachedSecY = sy;
                                    cachedSecZ = sz;
                                    cachedSectionMatches = sectionCache.loadMatchingSection(
                                            level, sx, sy, sz, minSecY, sectionsCount,
                                            random, targetCount, t0, t1, targetsArr, deterministicRules);
                                    cachedSection = sectionCache.lastSection;
                                    cachedStates = sectionCache.lastStates;
                                    cachedUniform = sectionCache.lastUniform;
                                    cachedUniformNonAir = sectionCache.lastUniformNonAir;
                                }
                                if (!cachedSectionMatches) {
                                    bz |= 15;
                                    continue;
                                }
                                section = cachedSection;
                                states = cachedStates;
                                secUniform = cachedUniform;
                                secUniformNonAir = cachedUniformNonAir;
                            }

                            int relZ = bz & 15;
                            BlockState state = secUniform != null ? secUniform : states.get(relX, relY, relZ);

                            if (deterministicRules) {
                                BlockState targetToPlace;
                                boolean resolved = false;
                                if (state == cacheKey0) {
                                    targetToPlace = cacheVal0;
                                    resolved = true;
                                } else if (state == cacheKey1) {
                                    targetToPlace = cacheVal1;
                                    resolved = true;
                                } else {
                                    targetToPlace = null;
                                    boolean cacheable = true;
                                    if (targetCount == 1) {
                                        if (rt0.test(state, random)) {
                                            targetToPlace = ts0;
                                        }
                                    } else if (targetCount == 2) {
                                        if (rt0.test(state, random)) {
                                            targetToPlace = ts0;
                                            if (!alwaysPlace && rt1.test(state, random)) {
                                                cacheable = false;
                                            }
                                        } else if (rt1.test(state, random)) {
                                            targetToPlace = ts1;
                                        }
                                    } else {
                                        for (int t = 0; t < targetCount; t++) {
                                            if (targetsArr[t].target.test(state, random)) {
                                                targetToPlace = targetsArr[t].state;
                                                if (!alwaysPlace) {
                                                    for (int t2 = t + 1; t2 < targetCount; t2++) {
                                                        if (targetsArr[t2].target.test(state, random)) {
                                                            cacheable = false;
                                                            break;
                                                        }
                                                    }
                                                }
                                                break;
                                            }
                                        }
                                    }
                                    if (cacheable) {
                                        cacheKey1 = cacheKey0;
                                        cacheVal1 = cacheVal0;
                                        cacheKey0 = state;
                                        cacheVal0 = targetToPlace;
                                        resolved = true;
                                    }
                                }

                                if (resolved) {
                                    if (targetToPlace != null
                                            && (alwaysPlace
                                            || shouldSkipAirCheck(random, discardChance)
                                            || !chunking$isAdjacentToAir(
                                            level, sectionCache, states, secUniformNonAir,
                                            bx, by, bz, relX, relY, relZ, minSecY, sectionsCount))) {
                                        section.setBlockState(relX, relY, relZ, targetToPlace, false);
                                        if (targetToPlace.isAir() && secUniformNonAir) {
                                            if (section == singleSection) {
                                                singleUniformNonAir = false;
                                            }
                                            if (section == cachedSection) {
                                                cachedUniformNonAir = false;
                                            }
                                            sectionCache.clearUniformNonAir(section);
                                        }
                                        placedCount++;
                                    }
                                    continue;
                                }
                            }

                            if (targetCount == 1) {
                                if (rt0.test(state, random)
                                        && (alwaysPlace
                                        || shouldSkipAirCheck(random, discardChance)
                                        || !chunking$isAdjacentToAir(
                                        level, sectionCache, states, secUniformNonAir,
                                        bx, by, bz, relX, relY, relZ, minSecY, sectionsCount))) {
                                    section.setBlockState(relX, relY, relZ, ts0, false);
                                    if (ts0.isAir() && secUniformNonAir) {
                                        if (section == singleSection) {
                                            singleUniformNonAir = false;
                                        }
                                        if (section == cachedSection) {
                                            cachedUniformNonAir = false;
                                        }
                                        sectionCache.clearUniformNonAir(section);
                                    }
                                    placedCount++;
                                }
                            } else if (targetCount == 2) {
                                if (rt0.test(state, random)
                                        && (alwaysPlace
                                        || shouldSkipAirCheck(random, discardChance)
                                        || !chunking$isAdjacentToAir(
                                        level, sectionCache, states, secUniformNonAir,
                                        bx, by, bz, relX, relY, relZ, minSecY, sectionsCount))) {
                                    section.setBlockState(relX, relY, relZ, ts0, false);
                                    if (ts0.isAir() && secUniformNonAir) {
                                        if (section == singleSection) {
                                            singleUniformNonAir = false;
                                        }
                                        if (section == cachedSection) {
                                            cachedUniformNonAir = false;
                                        }
                                        sectionCache.clearUniformNonAir(section);
                                    }
                                    placedCount++;
                                } else if (rt1.test(state, random)
                                        && (alwaysPlace
                                        || shouldSkipAirCheck(random, discardChance)
                                        || !chunking$isAdjacentToAir(
                                        level, sectionCache, states, secUniformNonAir,
                                        bx, by, bz, relX, relY, relZ, minSecY, sectionsCount))) {
                                    section.setBlockState(relX, relY, relZ, ts1, false);
                                    if (ts1.isAir() && secUniformNonAir) {
                                        if (section == singleSection) {
                                            singleUniformNonAir = false;
                                        }
                                        if (section == cachedSection) {
                                            cachedUniformNonAir = false;
                                        }
                                        sectionCache.clearUniformNonAir(section);
                                    }
                                    placedCount++;
                                }
                            } else {
                                for (int t = 0; t < targetCount; t++) {
                                    OreConfiguration.TargetBlockState targetState = targetsArr[t];
                                    if (targetState.target.test(state, random)
                                            && (alwaysPlace
                                            || shouldSkipAirCheck(random, discardChance)
                                            || !chunking$isAdjacentToAir(
                                            level, sectionCache, states, secUniformNonAir,
                                            bx, by, bz, relX, relY, relZ, minSecY, sectionsCount))) {
                                        BlockState toPlace = targetState.state;
                                        section.setBlockState(relX, relY, relZ, toPlace, false);
                                        if (toPlace.isAir() && secUniformNonAir) {
                                            if (section == singleSection) {
                                                singleUniformNonAir = false;
                                            }
                                            if (section == cachedSection) {
                                                cachedUniformNonAir = false;
                                            }
                                            sectionCache.clearUniformNonAir(section);
                                        }
                                        placedCount++;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            sectionCache.releaseAll();
            if (maxWordUsed >= 0) {
                Arrays.fill(bits, 0, maxWordUsed + 1, 0L);
            }
            scratch.inUse = false;
        }

        return placedCount > 0;
    }

    @Unique
    private static boolean chunking$isAirNeighbor(
            WorldGenLevel level,
            OreSectionCache sectionCache,
            PalettedContainer<BlockState> curStates,
            boolean curUniformNonAir,
            int x, int y, int z,
            int relX, int relY, int relZ,
            int minSecY, int sectionsCount
    ) {
        if (relX >= 0 && relX <= 15 && relY >= 0 && relY <= 15 && relZ >= 0 && relZ <= 15) {
            return !curUniformNonAir && curStates.get(relX, relY, relZ).isAir();
        }
        return sectionCache.getBlockState(level, x, y, z, minSecY, sectionsCount).isAir();
    }

    @Unique
    private static boolean chunking$isAdjacentToAir(
            WorldGenLevel level,
            OreSectionCache sectionCache,
            PalettedContainer<BlockState> curStates,
            boolean curUniformNonAir,
            int x, int y, int z,
            int relX, int relY, int relZ,
            int minSecY, int sectionsCount
    ) {
        if (curUniformNonAir && relX > 0 && relX < 15 && relY > 0 && relY < 15 && relZ > 0 && relZ < 15) {
            return false;
        }
        return chunking$isAirNeighbor(level, sectionCache, curStates, curUniformNonAir, x, y - 1, z, relX, relY - 1, relZ, minSecY, sectionsCount)
                || chunking$isAirNeighbor(level, sectionCache, curStates, curUniformNonAir, x, y + 1, z, relX, relY + 1, relZ, minSecY, sectionsCount)
                || chunking$isAirNeighbor(level, sectionCache, curStates, curUniformNonAir, x, y, z - 1, relX, relY, relZ - 1, minSecY, sectionsCount)
                || chunking$isAirNeighbor(level, sectionCache, curStates, curUniformNonAir, x, y, z + 1, relX, relY, relZ + 1, minSecY, sectionsCount)
                || chunking$isAirNeighbor(level, sectionCache, curStates, curUniformNonAir, x - 1, y, z, relX - 1, relY, relZ, minSecY, sectionsCount)
                || chunking$isAirNeighbor(level, sectionCache, curStates, curUniformNonAir, x + 1, y, z, relX + 1, relY, relZ, minSecY, sectionsCount);
    }
}
