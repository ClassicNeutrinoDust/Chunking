package com.chunking.engine.access;

import java.util.concurrent.locks.StampedLock;
import com.chunking.engine.world.LightMaps;

/**
 * Bridge interface implemented by {@link com.chunking.engine.mixin.LayerLightSectionStorageMixin}
 * so {@link com.chunking.engine.mixin.SkyLightSectionStorageMixin} can perform lock-free optimistic
 * reads via {@link StampedLock} over incrementally maintained visible lighting snapshots.
 */
public interface LightVisibleLockAccess {
    StampedLock chunking$visibleLock();

    LightMaps.ObjTable chunking$getVisObjTable();

    LightMaps.IntTable chunking$getVisTopTable();

    int chunking$getVisLowestY();

    void chunking$recordDirtySkyColumn(long colZeroNode);
}
