package com.chunking.engine.access;

/**
 * Direct O(1) cached full-block flag stored on {@code VoxelShape} ({@code 0 = unknown, 1 = false, 2 = true}).
 */
public interface VoxelShapeFlagAccess {
    byte chunking$getFullBlockFlag();

    void chunking$setFullBlockFlag(byte flag);
}
