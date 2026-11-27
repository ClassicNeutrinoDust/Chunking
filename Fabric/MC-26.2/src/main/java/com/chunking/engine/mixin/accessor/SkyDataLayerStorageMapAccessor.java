package com.chunking.engine.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes sky column top-section metadata on {@code SkyLightSectionStorage.SkyDataLayerStorageMap}
 * so {@code swapSectionMap()} can apply column updates incrementally without full-map clones.
 */
@Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
public interface SkyDataLayerStorageMapAccessor {
    @Accessor("topSections")
    Long2IntOpenHashMap chunking$getTopSections();

    @Accessor("currentLowestY")
    int chunking$getCurrentLowestY();

    @Accessor("currentLowestY")
    void chunking$setCurrentLowestY(int y);
}
