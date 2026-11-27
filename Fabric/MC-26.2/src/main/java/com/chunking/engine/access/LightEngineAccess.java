package com.chunking.engine.access;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;

/**
 * Exposes cached lookup and queue helpers on {@code LightEngine} subclasses.
 */
public interface LightEngineAccess {
    LightUpdatingAccess chunking$getStorage();

    LightChunk chunking$getChunk(int cx, int cz);

    default boolean chunking$isInLightUpdate() {
        return true;
    }

    BlockState chunking$getState(BlockPos pos);

    BlockState chunking$getState(int x, int y, int z, BlockPos.MutableBlockPos fallbackPos);

    int chunking$getOpacity(BlockState state);

    boolean chunking$shapeOccludes(BlockState fromState, BlockState toState, Direction dir);

    void chunking$enqueueIncrease(long pos, long entry);

    void chunking$enqueueDecrease(long pos, long entry);

    default int chunking$getEmission(long blockPos, BlockState state) {
        return 0;
    }
}
