package com.chunking.engine.mixin;

import com.chunking.engine.world.BlockTagBits;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Set;

/**
 * Replaces {@code HolderSet$Direct.contains} {@code Set.copyOf(...).contains(holder)} lookups
 * on bound {@link Block} sets with O(1) identity / {@code long[]} bitset checks.
 */
@Mixin(HolderSet.Direct.class)
public abstract class HolderSetDirectMixin<T> {
    @Shadow
    @Final
    private List<Holder<T>> contents;

    @Shadow
    private Set<Holder<T>> contentsSet;

    @Unique
    private volatile byte chunking$mode; // 0=uninit, 1=empty, 2=1 block, 3=2 blocks, 4=bitset, 5=fallback

    @Unique
    private Block chunking$b0;

    @Unique
    private Block chunking$b1;

    @Unique
    private long[] chunking$bits;

    @Unique
    private synchronized void chunking$initFast() {
        if (this.chunking$mode != 0) {
            return;
        }
        List<Holder<T>> list = this.contents;
        int n = list.size();
        if (n == 0) {
            this.chunking$mode = 1;
            return;
        }
        for (int i = 0; i < n; i++) {
            Holder<T> h = list.get(i);
            if (h.kind() != Holder.Kind.REFERENCE || !h.isBound()
                    || !(h.value() instanceof Block b)
                    || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(b) < 0) {
                this.chunking$mode = 5;
                return;
            }
        }
        if (n == 1) {
            this.chunking$b0 = (Block) list.get(0).value();
            this.chunking$mode = 2;
        } else if (n == 2) {
            this.chunking$b0 = (Block) list.get(0).value();
            this.chunking$b1 = (Block) list.get(1).value();
            this.chunking$mode = 3;
        } else {
            this.chunking$bits = BlockTagBits.build(list);
            this.chunking$mode = 4;
        }
    }

    /**
     * @author Chunking
     * @reason O(1) Block identity / bitset membership check for HolderSet.Direct.
     */
    @Overwrite
    public boolean contains(Holder<T> holder) {
        byte mode = this.chunking$mode;
        if (mode == 0) {
            chunking$initFast();
            mode = this.chunking$mode;
        }
        if (mode == 1) {
            return false;
        }
        if (mode >= 2 && mode <= 4 && holder.kind() == Holder.Kind.REFERENCE
                && holder.isBound() && holder.value() instanceof Block b) {
            if (mode == 2) {
                return b == this.chunking$b0;
            }
            if (mode == 3) {
                return b == this.chunking$b0 || b == this.chunking$b1;
            }
            return BlockTagBits.contains(this.chunking$bits, b);
        }
        if (this.contentsSet == null) {
            this.contentsSet = Set.copyOf(this.contents);
        }
        return this.contentsSet.contains(holder);
    }
}
