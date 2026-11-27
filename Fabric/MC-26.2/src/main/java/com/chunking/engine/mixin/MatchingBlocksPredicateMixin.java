package com.chunking.engine.mixin;

import com.chunking.engine.world.BlockTagBits;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.MatchingBlocksPredicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;

/**
 * Eliminates {@code BlockState.is(HolderSet) -> getBlockHolder() -> Set.contains} overhead
 * inside {@link MatchingBlocksPredicate#test(BlockState)} for direct block lists.
 */
@Mixin(MatchingBlocksPredicate.class)
public abstract class MatchingBlocksPredicateMixin {
    @Shadow
    @Final
    private HolderSet<Block> blocks;

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
        if (!(this.blocks instanceof HolderSet.Direct<Block> direct)) {
            this.chunking$mode = 5;
            return;
        }
        int n = direct.size();
        if (n == 0) {
            this.chunking$mode = 1;
            return;
        }
        List<Holder<Block>> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Holder<Block> h = direct.get(i);
            if (!h.isBound()
                    || h.kind() != Holder.Kind.REFERENCE
                    || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(h.value()) < 0) {
                this.chunking$mode = 5;
                return;
            }
            list.add(h);
        }
        if (n == 1) {
            this.chunking$b0 = list.get(0).value();
            this.chunking$mode = 2;
        } else if (n == 2) {
            this.chunking$b0 = list.get(0).value();
            this.chunking$b1 = list.get(1).value();
            this.chunking$mode = 3;
        } else {
            this.chunking$bits = BlockTagBits.build(list);
            this.chunking$mode = 4;
        }
    }

    /**
     * @author Chunking
     * @reason O(1) Block identity / bitset check in MatchingBlocksPredicate.
     */
    @Overwrite
    protected boolean test(BlockState state) {
        byte mode = this.chunking$mode;
        if (mode == 0) {
            chunking$initFast();
            mode = this.chunking$mode;
        }
        if (mode == 1) {
            return false;
        }
        if (mode == 2) {
            return state.getBlock() == this.chunking$b0;
        }
        if (mode == 3) {
            Block b = state.getBlock();
            return b == this.chunking$b0 || b == this.chunking$b1;
        }
        if (mode == 4) {
            return BlockTagBits.contains(this.chunking$bits, state.getBlock());
        }
        return state.is(this.blocks);
    }
}
