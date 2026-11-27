package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.CountOnEveryLayerAccessor;
import com.chunking.engine.mixin.accessor.EnvironmentScanPlacementAccessor;
import com.chunking.engine.mixin.accessor.HeightRangePlacementAccessor;
import com.chunking.engine.mixin.accessor.HeightmapPlacementAccessor;
import com.chunking.engine.mixin.accessor.PlacementFilterAccessor;
import com.chunking.engine.mixin.accessor.RandomOffsetPlacementAccessor;
import com.chunking.engine.mixin.accessor.RepeatingPlacementAccessor;
import com.chunking.engine.world.ModCompat;
import com.chunking.engine.world.FastPlacement;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeatureCountTracker;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Eliminates {@code Stream.of(pos).flatMap(...)} + {@code IntStream.range} + {@code MutableBoolean} +
 * {@code Spliterators} allocations (~700+ MB/5s) in {@link PlacedFeature#placeWithContext} across
 * {@link PlacementFilter}, {@link InSquarePlacement}, {@code HeightRangePlacement},
 * {@code HeightmapPlacement}, and {@code RepeatingPlacement} while preserving exact vanilla traversal order.
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {
    @Shadow
    @Final
    private Holder<ConfiguredFeature<?, ?>> feature;

    @Shadow
    @Final
    private List<PlacementModifier> placement;

    /**
     * @author Chunking
     * @reason Zero-stream placement evaluation with direct fast paths for InSquare/HeightRange/Heightmap/Repeating/Filter modifiers.
     */
    @Overwrite
    private boolean placeWithContext(PlacementContext context, RandomSource random, BlockPos pos) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            Stream<BlockPos> stream = Stream.of(pos);
            for (PlacementModifier modifier : this.placement) {
                stream = stream.flatMap(p -> modifier.getPositions(context, random, p));
            }
            ConfiguredFeature<?, ?> configured = this.feature.value();
            MutableBoolean placed = new MutableBoolean();
            stream.forEach(p -> {
                if (configured.place(context.getLevel(), context.generator(), random, p)) {
                    placed.setTrue();
                    if (SharedConstants.DEBUG_FEATURE_COUNT) {
                        FeatureCountTracker.featurePlaced(context.getLevel().getLevel(), configured, context.topFeature());
                    }
                }
            });
            return placed.isTrue();
        }
        return chunking$applyPlacement(
                this.placement, 0, context, random, pos.getX(), pos.getY(), pos.getZ(), this.feature.value());
    }

    @Unique
    private static final int CHUNKING$POOL_DEPTH = 16;

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos[]> CHUNKING$MUTABLE_POOL =
            ThreadLocal.withInitial(() -> {
                BlockPos.MutableBlockPos[] arr = new BlockPos.MutableBlockPos[CHUNKING$POOL_DEPTH];
                for (int i = 0; i < CHUNKING$POOL_DEPTH; i++) {
                    arr[i] = new BlockPos.MutableBlockPos();
                }
                return arr;
            });

    @Unique
    private static BlockPos.MutableBlockPos chunking$mutablePos(int depth, int x, int y, int z) {
        BlockPos.MutableBlockPos m = (depth < CHUNKING$POOL_DEPTH)
                ? CHUNKING$MUTABLE_POOL.get()[depth]
                : new BlockPos.MutableBlockPos();
        m.set(x, y, z);
        return m;
    }

    @Unique
    private static final class CoordBuf {
        int[] data = new int[192];
        int len;

        void push(int x, int y, int z) {
            int l = this.len;
            if (l + 3 > this.data.length) {
                this.data = java.util.Arrays.copyOf(this.data, this.data.length << 1);
            }
            this.data[l] = x;
            this.data[l + 1] = y;
            this.data[l + 2] = z;
            this.len = l + 3;
        }
    }

    @Unique
    private static final ThreadLocal<CoordBuf> CHUNKING$COORD_BUF = ThreadLocal.withInitial(CoordBuf::new);

    @Unique
    private static final ClassValue<Boolean> CHUNKING$SAFE_REPEATING = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod(
                        "getPositions",
                        PlacementContext.class,
                        RandomSource.class,
                        BlockPos.class
                ).getDeclaringClass() == net.minecraft.world.level.levelgen.placement.RepeatingPlacement.class;
            } catch (Throwable ignored) {
                return false;
            }
        }
    };

    @Unique
    private static boolean chunking$applyPlacement(
            List<PlacementModifier> mods,
            int idx,
            PlacementContext ctx,
            RandomSource random,
            int px,
            int py,
            int pz,
            ConfiguredFeature<?, ?> feat
    ) {
        if (idx >= mods.size()) {
            if (feat.place(ctx.getLevel(), ctx.generator(), random, new BlockPos(px, py, pz))) {
                if (SharedConstants.DEBUG_FEATURE_COUNT) {
                    FeatureCountTracker.featurePlaced(ctx.getLevel().getLevel(), feat, ctx.topFeature());
                }
                return true;
            }
            return false;
        }
        PlacementModifier mod = mods.get(idx);
        int nextIdx = idx + 1;
        boolean isVanilla = ModCompat.isVanillaClass(mod.getClass());
        // PlacementFilter.getPositions is final, so modded filters can bypass Stream allocation safely.
        if (mod instanceof PlacementFilter filter) {
            BlockPos filterPos = isVanilla
                    ? chunking$mutablePos(idx, px, py, pz)
                    : new BlockPos(px, py, pz);
            return ((PlacementFilterAccessor) filter).chunking$shouldPlace(ctx, random, filterPos)
                    && chunking$applyPlacement(mods, nextIdx, ctx, random, px, py, pz, feat);
        }
        if (mod instanceof RepeatingPlacementAccessor rep
                && (isVanilla || CHUNKING$SAFE_REPEATING.get(mod.getClass()))) {
            BlockPos repPos = isVanilla
                    ? chunking$mutablePos(idx, px, py, pz)
                    : new BlockPos(px, py, pz);
            int count = rep.chunking$count(random, repPos);
            boolean placed = false;
            for (int i = 0; i < count; i++) {
                if (chunking$applyPlacement(mods, nextIdx, ctx, random, px, py, pz, feat)) {
                    placed = true;
                }
            }
            return placed;
        }
        if (isVanilla) {
        if (mod instanceof InSquarePlacement) {
            int nx = random.nextInt(16) + px;
            int nz = random.nextInt(16) + pz;
            return chunking$applyPlacement(mods, nextIdx, ctx, random, nx, py, nz, feat);
        }
        if (mod instanceof HeightRangePlacementAccessor hr) {
            int y = hr.chunking$getHeight().sample(random, ctx);
            return chunking$applyPlacement(mods, nextIdx, ctx, random, px, y, pz, feat);
        }
        if (mod instanceof HeightmapPlacementAccessor hm) {
            int y = ctx.getHeight(hm.chunking$getHeightmap(), px, pz);
            if (y > ctx.getMinY()) {
                return chunking$applyPlacement(mods, nextIdx, ctx, random, px, y, pz, feat);
            }
            return false;
        }
        if (mod instanceof RandomOffsetPlacementAccessor ro) {
            IntProvider xz = ro.chunking$getXzSpread();
            int nx = px + xz.sample(random);
            int ny = py + ro.chunking$getYSpread().sample(random);
            int nz = pz + xz.sample(random);
            return chunking$applyPlacement(mods, nextIdx, ctx, random, nx, ny, nz, feat);
        }
        if (mod instanceof CountOnEveryLayerAccessor cel) {
            IntProvider countProvider = cel.chunking$getCount();
            CoordBuf buf = CHUNKING$COORD_BUF.get();
            int base = buf.len;
            int layer = 0;
            boolean foundAny;
            try {
                do {
                    foundAny = false;
                    for (int i = 0; i < countProvider.sample(random); i++) {
                        int x = random.nextInt(16) + px;
                        int z = random.nextInt(16) + pz;
                        int maxY = ctx.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                        int groundY = FastPlacement.findOnGroundYFast(ctx, x, maxY, z, layer);
                        if (groundY != Integer.MAX_VALUE) {
                            buf.push(x, groundY, z);
                            foundAny = true;
                        }
                    }
                    layer++;
                } while (foundAny);
                int end = buf.len;
                boolean placed = false;
                for (int off = base; off < end; off += 3) {
                    int[] d = buf.data;
                    if (chunking$applyPlacement(mods, nextIdx, ctx, random, d[off], d[off + 1], d[off + 2], feat)) {
                        placed = true;
                    }
                }
                return placed;
            } finally {
                buf.len = base;
            }
        }
        if (mod instanceof EnvironmentScanPlacementAccessor env) {
            BlockPos.MutableBlockPos m = chunking$mutablePos(idx, px, py, pz);
            WorldGenLevel level = ctx.getLevel();
            BlockPredicate allowed = env.chunking$getAllowedSearchCondition();
            if (!allowed.test(level, m)) {
                return false;
            }
            BlockPredicate target = env.chunking$getTargetCondition();
            Direction dir = env.chunking$getDirectionOfSearch();
            int maxSteps = env.chunking$getMaxSteps();
            for (int i = 0; i < maxSteps; i++) {
                if (target.test(level, m)) {
                    return chunking$applyPlacement(mods, nextIdx, ctx, random, m.getX(), m.getY(), m.getZ(), feat);
                }
                m.move(dir);
                if (level.isOutsideBuildHeight(m.getY())) {
                    return false;
                }
                if (!allowed.test(level, m)) {
                    break;
                }
            }
            if (target.test(level, m)) {
                return chunking$applyPlacement(mods, nextIdx, ctx, random, m.getX(), m.getY(), m.getZ(), feat);
            }
            return false;
        }
        }
        boolean placed = false;
        try (Stream<BlockPos> stream = mod.getPositions(ctx, random, new BlockPos(px, py, pz))) {
            Iterator<BlockPos> it = stream.iterator();
            while (it.hasNext()) {
                BlockPos p = it.next();
                if (chunking$applyPlacement(mods, nextIdx, ctx, random, p.getX(), p.getY(), p.getZ(), feat)) {
                    placed = true;
                }
            }
        }
        return placed;
    }
}
