package com.chunking.engine.access;

/**
 * Allows {@code ChunkMap} unload completion to evict unloaded chunk sections and membership sets from
 * {@code SectionStorage} / {@code PoiManager}, bounding memory usage to O(loaded) over 1M+ chunks.
 */
public interface SectionStorageEvictAccess {
    void chunking$evictUnloadedChunk(long packedChunkPos);
}
