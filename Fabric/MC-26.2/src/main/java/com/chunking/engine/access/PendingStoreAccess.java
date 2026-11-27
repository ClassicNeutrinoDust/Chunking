package com.chunking.engine.access;

import net.minecraft.nbt.CompoundTag;

/**
 * Exposes the stored {@link CompoundTag} reference on {@code IOWorker$PendingStore} without forced deep copying.
 */
public interface PendingStoreAccess {
    CompoundTag chunking$rawData();
}
