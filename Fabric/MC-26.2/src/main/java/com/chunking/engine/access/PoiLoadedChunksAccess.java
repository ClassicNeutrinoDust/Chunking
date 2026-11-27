package com.chunking.engine.access;

/**
 * Exposes {@code PoiManager}'s shadowed {@code loadedChunks} eviction to {@code SectionStorageMixin}.
 */
public interface PoiLoadedChunksAccess {
    void chunking$removeLoadedChunk(long packedChunkPos);
}
