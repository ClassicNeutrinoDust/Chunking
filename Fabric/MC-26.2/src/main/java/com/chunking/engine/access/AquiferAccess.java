package com.chunking.engine.access;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Exposes safe sky-air fast-skip bounds and uniform-fluid box checks on {@code Aquifer$NoiseBasedAquifer}.
 */
public interface AquiferAccess {
    int chunking$skipSamplingAboveY();

    boolean chunking$isGlobalFluidAirRange(int bx, int minY, int maxY, int bz);

    BlockState chunking$globalFluidAt(int bx, int by, int bz);

    BlockState chunking$getUniformBoxFluid(int x0, int y0, int z0, int cw, int ch);

    boolean chunking$isPreparedAllEqual();

    int chunking$preparedFluidLevel();

    BlockState chunking$preparedFluidType();

    boolean chunking$hasPreparedCell();

    boolean chunking$barrierNeedsNoiseChunk();

    BlockState chunking$computeVoxelFromCell(
            net.minecraft.world.level.levelgen.DensityFunction.FunctionContext ctx,
            int bx,
            int by,
            int bz,
            double dens
    );

    boolean chunking$fillCellBuf(
            net.minecraft.world.level.levelgen.NoiseChunk noiseChunk,
            double[] fullVals,
            BlockState[] outStates,
            long[] outFluidUpdateMask,
            int x0,
            int y0,
            int z0,
            int cw,
            int ch,
            double invCw,
            double invCh,
            boolean barrierNeedsChunk
    );
}
