package com.chunking.engine.access;

import net.minecraft.world.level.LightLayer;

/**
 * Coalesces off-thread ChunkHolder light section notifications into a per-chunk bitmask.
 */
public interface ChunkHolderLightAccess {
    void chunking$queueLightSectionDirty(LightLayer type, int sectionY);

    boolean chunking$shouldScheduleUndirty();

    boolean chunking$undirtyLight();
}
