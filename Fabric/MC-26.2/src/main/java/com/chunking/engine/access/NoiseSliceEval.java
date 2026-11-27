package com.chunking.engine.access;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import com.chunking.engine.simd.NoiseJit;

/**
 * Bridge implemented by {@code NoiseChunk} to run Java 25 compiled JIT kernels on vertical slice arrays,
 * share chunk-border vertical slices across adjacent chunks, and expose fast cell classification and
 * lazy ore-vein evaluation for {@code NoiseBasedChunkGenerator.doFill}.
 */
public interface NoiseSliceEval {
    NoiseJit.BoundKernel chunking$onFlatCacheInit(Object flatCache, DensityFunction filler);

    NoiseJit.BoundKernel chunking$resolveSliceKernel(NoiseChunk.NoiseInterpolator interp, DensityFunction fn);

    default int chunking$resolveInterpolatorOrdinal(NoiseChunk.NoiseInterpolator interp) {
        return -1;
    }

    boolean chunking$evalVerticalSlice(double[] out, NoiseJit.BoundKernel bound);

    default boolean chunking$evalVerticalSliceCached(
            double[] out,
            NoiseJit.BoundKernel bound,
            int interpOrdinal
    ) {
        return chunking$evalVerticalSlice(out, bound);
    }

    boolean chunking$evalVerticalSliceAt(double[] out, NoiseJit.BoundKernel bound, int bx, int bz, int maxCount);

    default boolean chunking$evalVerticalSliceAtCached(
            double[] out,
            NoiseJit.BoundKernel bound,
            int bx,
            int bz,
            int maxCount,
            int interpOrdinal
    ) {
        return chunking$evalVerticalSliceAt(out, bound, bx, bz, maxCount);
    }

    boolean chunking$tryFillVerticalSlice(double[] out, DensityFunction function);

    boolean chunking$isCurrentCellGuaranteedAir();

    boolean chunking$isCurrentCellAllNonPositive();

    boolean chunking$isCurrentCellAllPositive();

    boolean chunking$isCurrentCellGuaranteedSolidDefault();

    boolean chunking$isCurrentCellOreInactive();

    boolean chunking$canEvalFastOre();

    BlockState chunking$evalOreVeinAt(
            int bx,
            int by,
            int bz,
            int inX,
            int inY,
            int inZ,
            int cellIdx,
            BlockState defaultBlock
    );

    BlockState chunking$getInterpolatedStateFast();

    void chunking$setMaxSliceCount(int maxSliceCount);

    int chunking$getMaxSliceCount();

    int chunking$getOreVeinSliceCount(int fullLength);

    double[] chunking$getFullDensityCellValues();

    double[] chunking$getVeinToggleValues();

    double[] chunking$getVeinRidgedValues();

    double[] chunking$getVeinGapValues();

    net.minecraft.world.level.levelgen.PositionalRandomFactory chunking$getOreRandom();

    boolean chunking$isCellRangeGlobalFluidAir(int x0, int x1, int minY, int maxY, int z0, int z1);
}
