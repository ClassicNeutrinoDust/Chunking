package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Exposes the owning {@link NoiseChunk} from {@code NoiseChunk$1} without calling
 * {@code forIndex()} (which mutates {@code interpolationCounter} and {@code cellStartBlockY}).
 */
public interface SliceProviderAccess {
    NoiseChunk chunking$getNoiseChunk();
}
