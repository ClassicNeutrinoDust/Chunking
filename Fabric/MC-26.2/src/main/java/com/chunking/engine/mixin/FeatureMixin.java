package com.chunking.engine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Eliminates per-call {@link Direction#values()} array cloning and {@link BlockPos.MutableBlockPos}
 * heap allocations inside {@link Feature#checkNeighbors} and {@link Feature#isAdjacentToAir}.
 */
@Mixin(Feature.class)
public abstract class FeatureMixin {
    @Unique
    private static final Direction[] CHUNKING$DIRECTIONS = Direction.values();

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> CHUNKING$NEIGHBOR_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    /**
     * @author Chunking
     * @reason Zero-alloc neighbor check using cached Direction array and ThreadLocal MutableBlockPos.
     */
    @Overwrite
    public static boolean checkNeighbors(
            Function<BlockPos, BlockState> blockGetter,
            BlockPos pos,
            Predicate<BlockState> predicate
    ) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        BlockPos.MutableBlockPos mut = CHUNKING$NEIGHBOR_POS.get();
        for (Direction dir : CHUNKING$DIRECTIONS) {
            mut.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            if (predicate.test(blockGetter.apply(mut))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @author Chunking
     * @reason Zero-alloc adjacent-to-air check bypassing Predicate lambda capture and Direction.values() clone.
     */
    @Overwrite
    public static boolean isAdjacentToAir(
            Function<BlockPos, BlockState> blockGetter,
            BlockPos pos
    ) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        BlockPos.MutableBlockPos mut = CHUNKING$NEIGHBOR_POS.get();
        for (Direction dir : CHUNKING$DIRECTIONS) {
            mut.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            if (blockGetter.apply(mut).isAir()) {
                return true;
            }
        }
        return false;
    }
}
