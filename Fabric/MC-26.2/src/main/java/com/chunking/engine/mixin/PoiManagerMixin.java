package com.chunking.engine.mixin;

import com.chunking.engine.access.PoiLoadedChunksAccess;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Synchronizes {@link PoiManager}'s shadowed {@code loadedChunks} set and evicts unloaded
 * chunk entries when {@code SectionStorage} evicts a chunk on unload.
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin implements PoiLoadedChunksAccess {
    @Shadow
    @Final
    @Mutable
    private LongSet loadedChunks;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$synchronizePoiLoadedChunks(CallbackInfo ci) {
        this.loadedChunks = LongSets.synchronize(this.loadedChunks);
    }

    @Override
    public void chunking$removeLoadedChunk(long packedChunkPos) {
        this.loadedChunks.remove(packedChunkPos);
    }
}
