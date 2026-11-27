package com.chunking.engine.access;

/**
 * Exposes internal permutation/offset tables of {@code ImprovedNoise} so {@code BlendedNoise}
 * can hoist X/Z octave hashing and fade curves across vertical slices with zero per-point dispatch.
 */
public interface ImprovedNoiseAccess {
    byte[] chunking$perm();

    double chunking$xo();

    double chunking$yo();

    double chunking$zo();
}
