package com.chunking.engine.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the backing section map on {@link DataLayerStorageMap} so {@code swapSectionMap()}
 * can synchronize visible lighting state incrementally without full-map {@code clone()}.
 */
@Mixin(DataLayerStorageMap.class)
public interface DataLayerStorageMapAccessor {
    @Accessor("map")
    Long2ObjectOpenHashMap<DataLayer> chunking$getMap();
}
