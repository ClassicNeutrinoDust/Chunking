package com.chunking.engine.access;

/**
 * Bridge allowing {@code ServerChunkCacheGetChunkMixin} to track synchronous
 * {@code managedBlock} waits on {@code ServerChunkCacheMixin} without mixing into
 * Lithium's {@code @Overwrite ServerChunkCache.getChunk}.
 */
public interface SyncWaitDepthAccess {
    void chunking$enterSyncWait();

    void chunking$exitSyncWait();
}
