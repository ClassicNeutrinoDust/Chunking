package com.chunking.engine.access;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Bulk cell fill bridge implemented on {@code LevelChunkSection}.
 */
public interface LevelChunkSectionAccess {
    void chunking$fillUniformSolidCell(int localBaseX, int localBaseY, int localBaseZ, int cw, int ch, BlockState state);

    boolean chunking$fillMixedDensityCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cellBaseY,
            int cw,
            int ch,
            double[] fullVals,
            BlockState defaultState,
            BlockState fluidState,
            int fluidMaxExclusiveY
    );

    boolean chunking$writeStateCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cw,
            int ch,
            BlockState[] cellStates,
            BlockState defaultState
    );
}
