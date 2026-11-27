package com.chunking.engine.mixin;

import com.chunking.engine.access.VoxelShapeFlagAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces vanilla's global Guava LRU {@code Block.SHAPE_FULL_BLOCK_CACHE} lookup in
 * {@link Block#isShapeFullBlock(VoxelShape)} with identity fast-paths and an O(1) inline
 * cached flag directly on {@link VoxelShape} (unbounded capacity, zero map contention).
 */
@Mixin(Block.class)
public abstract class BlockShapeCacheMixin {
    @Inject(method = "isShapeFullBlock", at = @At("HEAD"), cancellable = true)
    private static void chunking$fastIsShapeFullBlock(
            VoxelShape shape,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (shape == Shapes.block()) {
            cir.setReturnValue(Boolean.TRUE);
            return;
        }
        if (shape == Shapes.empty() || shape == null) {
            cir.setReturnValue(Boolean.FALSE);
            return;
        }
        if ((Object) shape instanceof VoxelShapeFlagAccess acc) {
            byte flag = acc.chunking$getFullBlockFlag();
            if (flag != 0) {
                cir.setReturnValue(flag == 2 ? Boolean.TRUE : Boolean.FALSE);
                return;
            }
            boolean full = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
            acc.chunking$setFullBlockFlag(full ? (byte) 2 : (byte) 1);
            cir.setReturnValue(full ? Boolean.TRUE : Boolean.FALSE);
            return;
        }
        boolean full = !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
        cir.setReturnValue(full ? Boolean.TRUE : Boolean.FALSE);
    }
}
