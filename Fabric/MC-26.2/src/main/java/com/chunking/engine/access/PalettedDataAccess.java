package com.chunking.engine.access;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;

/**
 * Exposes storage and palette of {@code PalettedContainer$Data} for bulk cell writes.
 */
public interface PalettedDataAccess<T> {
    BitStorage chunking$storage();

    Palette<T> chunking$palette();
}
