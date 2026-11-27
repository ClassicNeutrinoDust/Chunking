package com.chunking.engine.mixin;

import com.chunking.engine.access.VoxelShapeFlagAccess;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Stores an inline 2-bit full-block flag directly on every {@link VoxelShape} so
 * {@code Block.isShapeFullBlock} scales to unbounded modded shapes in O(1) without map lookups.
 */
@Mixin(VoxelShape.class)
public abstract class VoxelShapeFlagMixin implements VoxelShapeFlagAccess {
    // 0 = unknown, 1 = false, 2 = true. Benign racy cache: byte access is atomic and the result is pure.
    // Initialized in <init> (mixin field)
    @Unique
    private byte chunking$fullBlockFlag;

    @Override
    public byte chunking$getFullBlockFlag() {
        return this.chunking$fullBlockFlag;
    }

    @Override
    public void chunking$setFullBlockFlag(byte flag) {
        this.chunking$fullBlockFlag = flag;
    }
}
