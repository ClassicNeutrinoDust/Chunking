package com.chunking.engine.mixin;

import com.chunking.engine.access.BlockIdAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Lazily caches {@link BuiltInRegistries#BLOCK} numeric ID on each {@link Block} instance for
 * lock-free O(1) {@code TagMatchTest} bitset checks during {@code OreFeature} placement.
 */
@Mixin(Block.class)
public abstract class BlockIdMixin implements BlockIdAccess {
    // Initialized in <init> (mixin field)
    // Stores (blockId + 1) so default 0 means uninitialized.
    @Unique
    private int chunking$blockIdPlusOne;

    @Override
    public int chunking$blockId() {
        int stored = this.chunking$blockIdPlusOne;
        if (stored > 0) {
            return stored - 1;
        }
        int id = BuiltInRegistries.BLOCK.getId((Block) (Object) this);
        if (id >= 0) {
            this.chunking$blockIdPlusOne = id + 1;
        }
        return id;
    }
}
