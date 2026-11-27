package com.chunking.engine.access;

/**
 * Provides thread-local {@code placeCount} storage for shared static {@code PieceWeight}
 * descriptors in {@code StrongholdPieces} and {@code NetherFortressPieces}.
 */
public interface PieceWeightAccess {
    ThreadLocal<Integer> chunking$placeCountTL();
}
