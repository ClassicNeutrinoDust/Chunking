package com.chunking.engine.mixin;

import com.chunking.engine.world.BlockTagBits;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Replaces {@code HolderSet$Named.contains -> Holder.is(TagKey) -> SetN.probe} hash lookups
 * on block tags (e.g. {@code MatchingBlocksPredicate}, {@code WorldCarver.canReplaceBlock})
 * with a cached O(1) {@code long[]} block-ID bitset, invalidated on {@code bind(...)}.
 */
@Mixin(HolderSet.Named.class)
public abstract class HolderSetNamedMixin<T> {
    @Unique
    private static final long[] CHUNKING$NEEDS_BUILD = new long[0];

    @Shadow
    @Final
    private TagKey<T> key;

    @Shadow
    private List<Holder<T>> contents;

    @Unique
    private volatile transient Object[] chunking$cachedBits;

    @Inject(method = "bind", at = @At("TAIL"))
    private void chunking$invalidateBlockBitsOnBind(List<Holder<T>> contents, CallbackInfo ci) {
        this.chunking$cachedBits = new Object[]{contents, CHUNKING$NEEDS_BUILD};
    }

    /**
     * @author Chunking
     * @reason O(1) block-ID bitset check for bound block tags with a single volatile read.
     */
    @Overwrite
    public boolean contains(Holder<T> holder) {
        Object[] cached = this.chunking$cachedBits;
        if (cached != null && holder.kind() == Holder.Kind.REFERENCE && holder.isBound() && holder.value() instanceof Block block) {
            long[] bits = (long[]) cached[1];
            if (bits == CHUNKING$NEEDS_BUILD || cached[0] != this.contents) {
                bits = this.chunking$getOrBuildBits();
            }
            return BlockTagBits.contains(bits, block);
        }
        return holder.is(this.key);
    }

    @Unique
    private synchronized long[] chunking$getOrBuildBits() {
        List<Holder<T>> cur = this.contents;
        Object[] cached = this.chunking$cachedBits;
        if (cached != null && cached[0] == cur && cached[1] != CHUNKING$NEEDS_BUILD) {
            return (long[]) cached[1];
        }
        long[] bits = BlockTagBits.build(cur);
        this.chunking$cachedBits = new Object[]{cur, bits};
        return bits;
    }
}
