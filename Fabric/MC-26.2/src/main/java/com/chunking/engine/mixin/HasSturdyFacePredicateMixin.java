package com.chunking.engine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.HasSturdyFacePredicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates {@code pos.offset(this.offset)} {@link BlockPos} allocations in {@link HasSturdyFacePredicate#test}.
 */
@Mixin(HasSturdyFacePredicate.class)
public abstract class HasSturdyFacePredicateMixin {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> CHUNKING$POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Shadow
    @Final
    private Vec3i offset;

    @Shadow
    @Final
    private Direction direction;

    /**
     * @author Chunking
     * @reason Avoid allocating a new {@link BlockPos} on every sturdy-face predicate check.
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        if (this.offset.getX() == 0 && this.offset.getY() == 0 && this.offset.getZ() == 0) {
            return level.getBlockState(pos).isFaceSturdy(level, pos, this.direction);
        }
        BlockPos.MutableBlockPos m = CHUNKING$POS.get();
        m.setWithOffset(pos, this.offset);
        return level.getBlockState(m).isFaceSturdy(level, m, this.direction);
    }
}
