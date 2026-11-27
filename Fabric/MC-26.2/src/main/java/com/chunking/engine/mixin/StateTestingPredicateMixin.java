package com.chunking.engine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates {@code pos.offset(this.offset)} {@link BlockPos} allocations in {@link StateTestingPredicate#test}.
 */
@Mixin(StateTestingPredicate.class)
public abstract class StateTestingPredicateMixin {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> CHUNKING$OFFSET_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    protected Vec3i offset;

    @Shadow
    protected abstract boolean test(BlockState state);

    /**
     * @author Chunking
     * @reason Avoid allocating a new {@link BlockPos} on every predicate check.
     */
    @Overwrite
    public final boolean test(WorldGenLevel level, BlockPos pos) {
        if (this.offset.getX() == 0 && this.offset.getY() == 0 && this.offset.getZ() == 0) {
            return this.test(level.getBlockState(pos));
        }
        BlockPos.MutableBlockPos m = CHUNKING$OFFSET_POS.get();
        m.setWithOffset(pos, this.offset);
        return this.test(level.getBlockState(m));
    }
}
