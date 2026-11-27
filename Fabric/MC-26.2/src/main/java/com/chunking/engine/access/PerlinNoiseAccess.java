package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Exposes pre-compacted active octaves and pre-multiplied scales from {@code PerlinNoise}.
 */
public interface PerlinNoiseAccess {
    ImprovedNoise[] chunking$activeOctaves();

    double[] chunking$activeInputFactors();

    double[] chunking$activeCombinedScales();
}
