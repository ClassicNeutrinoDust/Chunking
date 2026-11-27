package com.chunking.engine.access;

import net.minecraft.world.level.chunk.DataLayer;

/**
 * Exposes lock-free {@code updatingSectionData} lookups and cached single-section write preparation
 * on {@code LayerLightSectionStorage} for interior and border light propagation fast paths.
 */
public interface LightUpdatingAccess {
    DataLayer chunking$getUpdatingLayer(long secPos);

    DataLayer chunking$getOrCopyWritableLayer(long secPos);

    int chunking$getStoredLevelFast(long blockPos);

    void chunking$setStoredLevelFast(long blockPos, int level);

    void chunking$markBorderAffected(int bx, int by, int bz);

    default int chunking$getUpdatingTopSectionY(long zeroNode) {
        return 0;
    }

    default int chunking$getUpdatingBottomSectionY() {
        return 0;
    }
}
