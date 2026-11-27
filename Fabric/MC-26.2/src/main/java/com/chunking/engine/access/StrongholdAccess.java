package com.chunking.engine.access;

/**
 * Holds thread-local {@code imposedPiece} state for parallel {@code StrongholdPieces} generation.
 */
public final class StrongholdAccess {
    public static final ThreadLocal<Class<?>> IMPOSED_PIECE_TL = new ThreadLocal<>();

    private StrongholdAccess() {}
}
