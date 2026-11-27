package com.chunking.engine.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.GeodeBlockSettings;
import net.minecraft.world.level.levelgen.GeodeCrackSettings;
import net.minecraft.world.level.levelgen.GeodeLayerSettings;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Eliminates {@code LinkedList.listIterator()}, {@code Pair}/{@code Integer}, {@link BlockPos},
 * and per-geode {@link NormalNoise} allocations inside {@link GeodeFeature#place}, clamps the
 * scan volume around distribution points, and skips noise/crack evaluation whenever a voxel is
 * guaranteed outside the geode surface.
 */
@Mixin(GeodeFeature.class)
public abstract class GeodeFeatureMixin extends Feature<GeodeConfiguration> {
    /**
     * Exact upper bound on {@code |NormalNoise.create(rng, -4, 1.0D).getValue(x, y, z)|}:
     * each {@code ImprovedNoise} octave is bounded in {@code [-1, 1]} and {@code valueFactor = 5/6},
     * so {@code |value| <= 2 * 5/6 = 1.6666... < 1.67}.
     */
    @Unique
    private static final double CHUNKING$MAX_NORMAL_NOISE_ABS = 1.67D;

    @Unique
    private static final class CachedNoise {
        final long seed;
        final NormalNoise noise;

        CachedNoise(long seed, NormalNoise noise) {
            this.seed = seed;
            this.noise = noise;
        }
    }

    @Unique
    private static volatile CachedNoise CHUNKING$NOISE_0;

    @Unique
    private static volatile CachedNoise CHUNKING$NOISE_1;

    @Unique
    private static NormalNoise chunking$getOrCreateNoise(long seed) {
        CachedNoise c0 = CHUNKING$NOISE_0;
        if (c0 != null && c0.seed == seed) {
            return c0.noise;
        }
        CachedNoise c1 = CHUNKING$NOISE_1;
        if (c1 != null && c1.seed == seed) {
            return c1.noise;
        }
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(seed));
        NormalNoise created = NormalNoise.create(worldgenRandom, -4, 1.0D);
        CachedNoise entry = new CachedNoise(seed, created);
        CHUNKING$NOISE_1 = c0;
        CHUNKING$NOISE_0 = entry;
        return created;
    }

    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    protected GeodeFeatureMixin(Codec<GeodeConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Chunking
     * @reason Cache seed-dependent NormalNoise, clamp scan bounds, and early-out before noise evaluation.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<GeodeConfiguration> context) {
        GeodeConfiguration config = context.config();
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        WorldGenLevel level = context.level();
        int minOffset = config.minGenOffset();
        int maxOffset = config.maxGenOffset();

        int dpCount = config.distributionPoints().sample(random);
        NormalNoise normalNoise = chunking$getOrCreateNoise(level.getSeed());

        double outerWallFactor = (double) dpCount / (double) config.outerWallDistance().maxInclusive();
        GeodeLayerSettings layerSettings = config.geodeLayerSettings();
        GeodeBlockSettings blockSettings = config.geodeBlockSettings();
        GeodeCrackSettings crackSettings = config.geodeCrackSettings();
        double fillingThreshold = 1.0D / Math.sqrt(layerSettings.filling);
        double innerThreshold = 1.0D / Math.sqrt(layerSettings.innerLayer + outerWallFactor);
        double middleThreshold = 1.0D / Math.sqrt(layerSettings.middleLayer + outerWallFactor);
        double outerThreshold = 1.0D / Math.sqrt(layerSettings.outerLayer + outerWallFactor);
        double crackThreshold = 1.0D / Math.sqrt(
                crackSettings.baseCrackSize + random.nextDouble() / 2.0D + (dpCount > 3 ? outerWallFactor : 0.0D));
        boolean generateCrack = (double) random.nextFloat() < crackSettings.generateCrackChance;

        int[] dpX = new int[dpCount];
        int[] dpY = new int[dpCount];
        int[] dpZ = new int[dpCount];
        int[] dpOff = new int[dpCount];
        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos adjPos = new BlockPos.MutableBlockPos();

        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();
        HolderSet<Block> invalidBlocks = blockSettings.invalidBlocks();
        int invalidCount = 0;

        int minDpX = Integer.MAX_VALUE;
        int maxDpX = Integer.MIN_VALUE;
        int minDpY = Integer.MAX_VALUE;
        int maxDpY = Integer.MIN_VALUE;
        int minDpZ = Integer.MAX_VALUE;
        int maxDpZ = Integer.MIN_VALUE;
        int minDpOff = Integer.MAX_VALUE;

        for (int i = 0; i < dpCount; i++) {
            int px = originX + config.outerWallDistance().sample(random);
            int py = originY + config.outerWallDistance().sample(random);
            int pz = originZ + config.outerWallDistance().sample(random);
            mutPos.set(px, py, pz);
            BlockState state = level.getBlockState(mutPos);
            if (state.isAir() || state.is(invalidBlocks)) {
                invalidCount++;
                if (invalidCount > config.invalidBlocksThreshold()) {
                    return false;
                }
            }
            dpX[i] = px;
            dpY[i] = py;
            dpZ[i] = pz;
            int off = config.pointOffset().sample(random);
            dpOff[i] = off;
            if (px < minDpX) minDpX = px;
            if (px > maxDpX) maxDpX = px;
            if (py < minDpY) minDpY = py;
            if (py > maxDpY) maxDpY = py;
            if (pz < minDpZ) minDpZ = pz;
            if (pz > maxDpZ) maxDpZ = pz;
            if (off < minDpOff) minDpOff = off;
        }

        int[] cpX = null;
        int[] cpY = null;
        int[] cpZ = null;
        if (generateCrack) {
            cpX = new int[3];
            cpY = new int[3];
            cpZ = new int[3];
            int dirIdx = random.nextInt(4);
            int crackSpan = dpCount * 2 + 1;
            int ox = (dirIdx == 0 || dirIdx == 2) ? originX + crackSpan : originX;
            int oz = (dirIdx == 1 || dirIdx == 2) ? originZ + crackSpan : originZ;
            cpX[0] = ox; cpY[0] = originY + 7; cpZ[0] = oz;
            cpX[1] = ox; cpY[1] = originY + 5; cpZ[1] = oz;
            cpX[2] = ox; cpY[2] = originY + 1; cpZ[2] = oz;
        }

        List<BlockPos> potentialCrystalPlacements = new ArrayList<>();
        HolderSet<Block> cannotReplace = blockSettings.cannotReplace();
        Predicate<BlockState> canReplace = state -> !state.is(cannotReplace);
        double noiseMultiplier = config.noiseMultiplier();
        int crackPointOffset = crackSettings.crackPointOffset;

        int minX = originX + minOffset;
        int maxX = originX + maxOffset;
        int minY = originY + minOffset;
        int maxY = originY + maxOffset;
        int minZ = originZ + minOffset;
        int maxZ = originZ + maxOffset;

        double minThreshold = Math.min(Math.min(fillingThreshold, innerThreshold), Math.min(middleThreshold, outerThreshold));
        double maxNoiseContribution = (double) dpCount * CHUNKING$MAX_NORMAL_NOISE_ABS * Math.abs(noiseMultiplier);
        double minNeededInvSqrt = minThreshold - maxNoiseContribution;
        double minNeededCrackInvSqrt = crackThreshold - 3.0D * CHUNKING$MAX_NORMAL_NOISE_ABS * Math.abs(noiseMultiplier);
        if (dpCount > 0 && minDpOff >= 0 && minNeededInvSqrt > 0.0D) {
            int maxReach = Mth.ceil((double) dpCount / minNeededInvSqrt) + 1;
            int clampMinX = minDpX - maxReach;
            int clampMaxX = maxDpX + maxReach;
            int clampMinY = minDpY - maxReach;
            int clampMaxY = maxDpY + maxReach;
            int clampMinZ = minDpZ - maxReach;
            int clampMaxZ = maxDpZ + maxReach;
            boolean canClamp = true;
            if (generateCrack) {
                if (crackPointOffset >= 0 && minNeededCrackInvSqrt > 0.0D) {
                    int crackReach = Mth.ceil(3.0D / minNeededCrackInvSqrt) + 1;
                    clampMinX = Math.min(clampMinX, cpX[0] - crackReach);
                    clampMaxX = Math.max(clampMaxX, cpX[0] + crackReach);
                    clampMinY = Math.min(clampMinY, cpY[2] - crackReach);
                    clampMaxY = Math.max(clampMaxY, cpY[0] + crackReach);
                    clampMinZ = Math.min(clampMinZ, cpZ[0] - crackReach);
                    clampMaxZ = Math.max(clampMaxZ, cpZ[0] + crackReach);
                } else {
                    canClamp = false;
                }
            }
            if (canClamp) {
                minX = Math.max(minX, clampMinX);
                maxX = Math.min(maxX, clampMaxX);
                minY = Math.max(minY, clampMinY);
                maxY = Math.min(maxY, clampMaxY);
                minZ = Math.max(minZ, clampMinZ);
                maxZ = Math.min(maxZ, clampMaxZ);
            }
        }

        // Preserve exact BlockPos.betweenClosed Z -> Y -> X traversal order for RNG & placement parity.
        for (int bz = minZ; bz <= maxZ; bz++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bx = minX; bx <= maxX; bx++) {
                    double invSqrtSum = 0.0D;
                    for (int i = 0; i < dpCount; i++) {
                        double dx = bx - dpX[i];
                        double dy = by - dpY[i];
                        double dz = bz - dpZ[i];
                        invSqrtSum += Mth.invSqrt(dx * dx + dy * dy + dz * dz + (double) dpOff[i]);
                    }
                    double crackInvSqrtSum = 0.0D;
                    boolean mayCrack = false;
                    if (generateCrack) {
                        for (int i = 0; i < 3; i++) {
                            double dx = bx - cpX[i];
                            double dy = by - cpY[i];
                            double dz = bz - cpZ[i];
                            crackInvSqrtSum += Mth.invSqrt(dx * dx + dy * dy + dz * dz + (double) crackPointOffset);
                        }
                        mayCrack = crackInvSqrtSum >= minNeededCrackInvSqrt;
                    }
                    if (invSqrtSum < minNeededInvSqrt && !mayCrack) {
                        continue;
                    }

                    double noiseVal = noiseMultiplier == 0.0D
                            ? 0.0D
                            : normalNoise.getValue(bx, by, bz) * noiseMultiplier;
                    double distSum = invSqrtSum + (double) dpCount * noiseVal;
                    double crackSum = mayCrack ? (crackInvSqrtSum + 3.0D * noiseVal) : 0.0D;
                    if (distSum < minThreshold && (!mayCrack || crackSum < crackThreshold)) {
                        continue;
                    }

                    mutPos.set(bx, by, bz);
                    if (generateCrack && crackSum >= crackThreshold && distSum < fillingThreshold) {
                        this.safeSetBlock(level, mutPos, Blocks.AIR.defaultBlockState(), canReplace);
                        for (Direction dir : DIRECTIONS) {
                            adjPos.set(bx + dir.getStepX(), by + dir.getStepY(), bz + dir.getStepZ());
                            FluidState fluid = level.getFluidState(adjPos);
                            if (!fluid.isEmpty()) {
                                level.scheduleTick(adjPos.immutable(), fluid.getType(), 0);
                            }
                        }
                    } else if (distSum >= fillingThreshold) {
                        this.safeSetBlock(level, mutPos, blockSettings.fillingProvider().getState(level, random, mutPos), canReplace);
                    } else if (distSum >= innerThreshold) {
                        boolean useAlternate = (double) random.nextFloat() < config.useAlternateLayer0Chance();
                        if (useAlternate) {
                            this.safeSetBlock(level, mutPos, blockSettings.alternateInnerLayerProvider().getState(level, random, mutPos), canReplace);
                        } else {
                            this.safeSetBlock(level, mutPos, blockSettings.innerLayerProvider().getState(level, random, mutPos), canReplace);
                        }
                        if ((!config.placementsRequireLayer0Alternate() || useAlternate)
                                && (double) random.nextFloat() < config.usePotentialPlacementsChance()) {
                            potentialCrystalPlacements.add(mutPos.immutable());
                        }
                    } else if (distSum >= middleThreshold) {
                        this.safeSetBlock(level, mutPos, blockSettings.middleLayerProvider().getState(level, random, mutPos), canReplace);
                    } else if (distSum >= outerThreshold) {
                        this.safeSetBlock(level, mutPos, blockSettings.outerLayerProvider().getState(level, random, mutPos), canReplace);
                    }
                }
            }
        }

        List<BlockState> innerPlacements = blockSettings.innerPlacements();
        for (int i = 0, n = potentialCrystalPlacements.size(); i < n; i++) {
            BlockPos crystalBase = potentialCrystalPlacements.get(i);
            BlockState budState = Util.getRandom(innerPlacements, random);
            for (Direction dir : DIRECTIONS) {
                if (budState.hasProperty(BlockStateProperties.FACING)) {
                    budState = budState.setValue(BlockStateProperties.FACING, dir);
                }
                adjPos.set(
                        crystalBase.getX() + dir.getStepX(),
                        crystalBase.getY() + dir.getStepY(),
                        crystalBase.getZ() + dir.getStepZ());
                BlockState adjState = level.getBlockState(adjPos);
                if (budState.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    budState = budState.setValue(BlockStateProperties.WATERLOGGED, adjState.getFluidState().isSource());
                }
                if (BuddingAmethystBlock.canClusterGrowAtState(adjState)) {
                    this.safeSetBlock(level, adjPos.immutable(), budState, canReplace);
                    break;
                }
            }
        }

        return true;
    }
}
