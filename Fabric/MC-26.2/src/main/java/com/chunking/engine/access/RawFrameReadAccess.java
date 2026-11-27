package com.chunking.engine.access;

import net.minecraft.world.level.ChunkPos;

import java.io.IOException;

/**
 * Raw RFC-1950 deflate frame reader implemented on {@code RegionFile} and {@code RegionFileStorage}
 * so {@code IOWorker} can read compressed sector bytes quickly and offload zlib inflation and
 * NBT decoding to parallel worker threads.
 */
public interface RawFrameReadAccess {
    byte[] EMPTY_ABSENT_FRAME = new byte[0];

    /** Returns EMPTY_ABSENT_FRAME if absent, a deflate frame if internal, or null to fall back to vanilla read. */
    byte[] chunking$readRawDeflateFrame(ChunkPos pos) throws IOException;
}
