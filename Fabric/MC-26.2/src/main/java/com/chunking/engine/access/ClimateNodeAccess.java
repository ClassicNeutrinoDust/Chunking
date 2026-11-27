package com.chunking.engine.access;

import net.minecraft.world.level.biome.Climate;

/**
 * Accessor bridge for {@code Climate$RTree$Node} and {@code Climate$RTree$Leaf}.
 */
public interface ClimateNodeAccess {
    Climate.Parameter[] chunking$parameterSpace();

    long chunking$distanceFast(long[] target);

    default Object chunking$leafValue() {
        return null;
    }

    default ClimateNodeAccess[] chunking$children() {
        return null;
    }
}
