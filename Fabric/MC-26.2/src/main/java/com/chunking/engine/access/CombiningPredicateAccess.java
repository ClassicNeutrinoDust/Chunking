package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

import java.util.List;

/**
 * Exposes {@code CombiningPredicate.predicates} without reflection or package-visibility issues.
 */
public interface CombiningPredicateAccess {
    List<BlockPredicate> chunking$predicates();
}
