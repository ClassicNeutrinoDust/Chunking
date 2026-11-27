package com.chunking.engine.mixin;

import com.chunking.engine.access.CombiningPredicateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.List;

/**
 * Replaces iterator-allocating {@code for (BlockPredicate p : this.predicates)} in {@code AllOfPredicate#test}
 * with an indexed loop (zero {@code ImmutableCollections$ListItr} allocations).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.AllOfPredicate")
public abstract class AllOfPredicateMixin {
    /**
     * @author Chunking
     * @reason Zero-allocation indexed loop over predicates.
     */
    @Overwrite
    public boolean test(WorldGenLevel level, BlockPos pos) {
        List<BlockPredicate> list = ((CombiningPredicateAccess) (Object) this).chunking$predicates();
        for (int i = 0, n = list.size(); i < n; i++) {
            if (!list.get(i).test(level, pos)) {
                return false;
            }
        }
        return true;
    }
}
