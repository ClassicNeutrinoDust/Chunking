package com.chunking.engine.world;

import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import java.lang.reflect.Field;

/**
 * Direct cell-array evaluator matching {@code net.minecraft.world.level.levelgen.OreVeinifier}
 * bit-for-bit without per-voxel virtual {@code DensityFunction.compute} or slice coordinate updates.
 */
public final class FastOreVeins {
    private static final float VEININESS_THRESHOLD = 0.4F;
    private static final float VEIN_SOLIDNESS = 0.7F;
    private static final float MIN_RICHNESS = 0.1F;
    private static final float MAX_RICHNESS = 0.3F;
    private static final float MAX_RICHNESS_THRESHOLD = 0.6F;
    private static final float CHANCE_OF_RAW_ORE_BLOCK = 0.02F;
    private static final float SKIP_ORE_IF_GAP_NOISE_IS_BELOW = -0.3F;

    private static final BlockState COPPER_ORE = Blocks.COPPER_ORE.defaultBlockState();
    private static final BlockState COPPER_RAW = Blocks.RAW_COPPER_BLOCK.defaultBlockState();
    private static final BlockState COPPER_FILLER = Blocks.GRANITE.defaultBlockState();
    private static final int COPPER_MIN_Y = 0;
    private static final int COPPER_MAX_Y = 50;

    private static final BlockState IRON_ORE = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
    private static final BlockState IRON_RAW = Blocks.RAW_IRON_BLOCK.defaultBlockState();
    private static final BlockState IRON_FILLER = Blocks.TUFF.defaultBlockState();
    private static final int IRON_MIN_Y = -60;
    private static final int IRON_MAX_Y = -8;

    private static final BlockState DEBUG_FILLER = Blocks.OAK_BUTTON.defaultBlockState();

    public static final int MIN_VEIN_Y;
    public static final int MAX_VEIN_Y;
    public static final boolean IS_VANILLA_VEINS;

    static {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean vanilla = false;
        try {
            Class<?> veinTypeCls = Class.forName("net.minecraft.world.level.levelgen.OreVeinifier$VeinType");
            Field fMin = veinTypeCls.getDeclaredField("minY");
            Field fMax = veinTypeCls.getDeclaredField("maxY");
            fMin.setAccessible(true);
            fMax.setAccessible(true);
            Object[] vals = veinTypeCls.getEnumConstants();
            boolean hasCopper = false;
            boolean hasIron = false;
            for (Object vt : vals) {
                int lo = fMin.getInt(vt);
                int hi = fMax.getInt(vt);
                if (lo < minY) minY = lo;
                if (hi > maxY) maxY = hi;
                String name = ((Enum<?>) vt).name();
                if ("COPPER".equals(name) && lo == COPPER_MIN_Y && hi == COPPER_MAX_Y) {
                    hasCopper = true;
                } else if ("IRON".equals(name) && lo == IRON_MIN_Y && hi == IRON_MAX_Y) {
                    hasIron = true;
                }
            }
            vanilla = (vals.length == 2 && hasCopper && hasIron);
        } catch (Throwable ignored) {
            minY = IRON_MIN_Y;
            maxY = COPPER_MAX_Y;
            vanilla = false;
        }
        if (minY > maxY) {
            minY = IRON_MIN_Y;
            maxY = COPPER_MAX_Y;
        }
        MIN_VEIN_Y = minY;
        MAX_VEIN_Y = maxY;
        IS_VANILLA_VEINS = vanilla;
    }

    private FastOreVeins() {}

    public static boolean isOutsideOreVeinY(int y) {
        if (IS_VANILLA_VEINS) {
            return y > COPPER_MAX_Y || y < IRON_MIN_Y || (y > IRON_MAX_Y && y < COPPER_MIN_Y);
        }
        return y > MAX_VEIN_Y || y < MIN_VEIN_Y;
    }

    public static boolean isRangeOutsideOreVeinY(int startY, int endY) {
        if (IS_VANILLA_VEINS) {
            return startY > COPPER_MAX_Y || endY < IRON_MIN_Y || (startY >= IRON_MAX_Y + 1 && endY <= COPPER_MIN_Y - 1);
        }
        return startY > MAX_VEIN_Y || endY < MIN_VEIN_Y;
    }

    public static BlockState selectOreBlock(boolean isCopper, boolean isRaw) {
        if (isCopper) {
            return isRaw ? COPPER_RAW : COPPER_ORE;
        }
        return isRaw ? IRON_RAW : IRON_ORE;
    }

    public static BlockState selectFillerBlock(boolean isCopper) {
        if (SharedConstants.DEBUG_ORE_VEINS) {
            return DEBUG_FILLER;
        }
        return isCopper ? COPPER_FILLER : IRON_FILLER;
    }

    public static BlockState eval(
            int bx,
            int by,
            int bz,
            double veinToggle,
            double veinRidged,
            double veinGap,
            PositionalRandomFactory oreRandom,
            BlockState defaultBlock
    ) {
        final int minY;
        final int maxY;
        final BlockState ore;
        final BlockState rawOre;
        final BlockState filler;
        if (veinToggle > 0.0D) {
            minY = COPPER_MIN_Y;
            maxY = COPPER_MAX_Y;
            ore = COPPER_ORE;
            rawOre = COPPER_RAW;
            filler = COPPER_FILLER;
        } else {
            minY = IRON_MIN_Y;
            maxY = IRON_MAX_Y;
            ore = IRON_ORE;
            rawOre = IRON_RAW;
            filler = IRON_FILLER;
        }
        int dMax = maxY - by;
        int dMin = by - minY;
        if (dMin < 0 || dMax < 0) {
            return defaultBlock;
        }
        double absVt = Math.abs(veinToggle);
        int minDist = Math.min(dMax, dMin);
        double edgeRoundoff = Mth.clampedMap((double) minDist, 0.0D, 20.0D, -0.2D, 0.0D);
        if (absVt + edgeRoundoff < (double) VEININESS_THRESHOLD) {
            return defaultBlock;
        }
        // Safe to short-circuit before PRNG since PositionalRandomFactory.at(bx, by, bz) is stateless per block
        if (veinRidged >= 0.0D) {
            return defaultBlock;
        }
        RandomSource rng = oreRandom.at(bx, by, bz);
        if (rng.nextFloat() > VEIN_SOLIDNESS) {
            return defaultBlock;
        }
        double richness = Mth.clampedMap(absVt, (double) VEININESS_THRESHOLD, (double) MAX_RICHNESS_THRESHOLD,
                (double) MIN_RICHNESS, (double) MAX_RICHNESS);
        if ((double) rng.nextFloat() < richness && veinGap > (double) SKIP_ORE_IF_GAP_NOISE_IS_BELOW) {
            return rng.nextFloat() < CHANCE_OF_RAW_ORE_BLOCK ? rawOre : ore;
        }
        return SharedConstants.DEBUG_ORE_VEINS ? DEBUG_FILLER : filler;
    }
}
