package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.HolderSetNamedAccessor;
import com.chunking.engine.world.BlockTagBits;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Optional;

/**
 * Replaces per-voxel {@code TagMatchTest.test -> Holder.is(TagKey) -> SetN.probe} hash lookups
 * (~2.7% CPU during {@code OreFeature}) with a compact cached {@code long[]} block-ID bitset.
 */
@Mixin(TagMatchTest.class)
public abstract class TagMatchTestMixin {
    @Shadow
    @Final
    private TagKey<Block> tag;

    @Unique
    private volatile transient Object[] chunking$cached;

    /**
     * @author Chunking
     * @reason O(1) cached block-ID bitset check for TagMatchTest.
     */
    @Overwrite
    public boolean test(BlockState state, RandomSource random) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return state.is(this.tag);
        }
        Object[] c = this.chunking$cached;
        if (c != null) {
            @SuppressWarnings("unchecked")
            HolderSet.Named<Block> named = (HolderSet.Named<Block>) c[0];
            if (((HolderSetNamedAccessor) (Object) named).chunking$getContents() == c[1]) {
                return BlockTagBits.contains((long[]) c[2], state.getBlock());
            }
        }
        return this.chunking$rebuildAndTest(state);
    }

    @Unique
    private synchronized boolean chunking$rebuildAndTest(BlockState state) {
        Optional<HolderSet.Named<Block>> opt = BuiltInRegistries.BLOCK.get(this.tag);
        if (opt.isEmpty()) {
            return state.is(this.tag);
        }
        HolderSet.Named<Block> named = opt.get();
        if (!named.isBound()) {
            return state.is(this.tag);
        }
        List<Holder<?>> contents = ((HolderSetNamedAccessor) (Object) named).chunking$getContents();
        if (contents == null) {
            return state.is(this.tag);
        }
        long[] bits = BlockTagBits.build(contents);
        this.chunking$cached = new Object[]{named, contents, bits};

        return BlockTagBits.contains(bits, state.getBlock());
    }
}
