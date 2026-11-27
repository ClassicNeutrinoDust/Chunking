package com.chunking.engine.mixin;

import com.chunking.engine.access.PendingStoreAccess;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.chunk.storage.IOWorker$PendingStore")
public abstract class IOWorkerPendingStoreMixin implements PendingStoreAccess {
    @Shadow
    private CompoundTag data;

    @Override
    public CompoundTag chunking$rawData() {
        return this.data;
    }
}
