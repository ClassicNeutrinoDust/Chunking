package com.chunking.engine.access;

import net.minecraft.world.level.chunk.Strategy;

import java.util.function.Consumer;

/**
 * Direct cell batch-fill, data/strategy access, and O(1) uniform single-value contract implemented on {@code PalettedContainer}.
 */
public interface PalettedContainerAccess<T> {
    default Strategy<T> chunking$strategy() {
        return null;
    }

    default PalettedDataAccess<T> chunking$dataAccess() {
        return null;
    }

    default boolean chunking$trySetUniformSingleValue(T value) {
        return false;
    }

    default T chunking$getSingleUniformValue() {
        return null;
    }

    int chunking$fillCellBox(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cw,
            int ch,
            T value,
            T airValue,
            Consumer<T> nonAirOverwriteHandler
    );

    /**
     * Writes an already-evaluated density cell ({@code fullVals[cellIdx] > 0 -> defaultVal}, else optional {@code fluidVal})
     * directly into the underlying {@link net.minecraft.util.BitStorage}. Returns {@code -1L} on fallback,
     * otherwise packs {@code ((long) fluidWritten << 32) | (solidWritten & 0xFFFFFFFFL)} (count of previously-air voxels).
     */
    long chunking$fillMixedDensityCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cellBaseY,
            int cw,
            int ch,
            double[] fullVals,
            T defaultVal,
            T fluidVal,
            int fluidMaxExclusiveY,
            T airValue,
            Consumer<T> nonAirOverwriteHandler
    );

    /**
     * Writes a sparse per-voxel cell buffer ({@code null} entries skipped) directly into {@code BitStorage}.
     * Returns {@code -1} on fallback; otherwise returns count of previously-air non-air blocks written,
     * filling {@code counts[0]=tickingAdded, counts[1]=fluidAdded, counts[2]=tickingFluidAdded}.
     */
    int chunking$writeStateCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cw,
            int ch,
            T[] cellStates,
            T defaultVal,
            T airValue,
            int[] outExtraCounts
    );
}
