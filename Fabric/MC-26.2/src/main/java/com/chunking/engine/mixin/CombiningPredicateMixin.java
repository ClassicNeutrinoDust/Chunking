package com.chunking.engine.mixin;

import com.chunking.engine.access.CombiningPredicateAccess;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.CombiningPredicate")
public abstract class CombiningPredicateMixin implements CombiningPredicateAccess {
    @Shadow
    @Final
    protected List<BlockPredicate> predicates;

    @Override
    public List<BlockPredicate> chunking$predicates() {
        return this.predicates;
    }
}
