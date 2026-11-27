package com.chunking.engine.access;

/**
 * Exposes cached registry integer IDs on {@code Block} instances for O(1) bitset tag membership checks.
 */
public interface BlockIdAccess {
    int chunking$blockId();
}
