package com.chunking.engine.access;

/**
 * Exposes flattened rule array from {@code SurfaceRules$SequenceRule} so enclosing sequences
 * can inline nested rule sequences without multi-level indirection.
 */
public interface SequenceRuleAccess {
    Object[] chunking$flatRules();
}
