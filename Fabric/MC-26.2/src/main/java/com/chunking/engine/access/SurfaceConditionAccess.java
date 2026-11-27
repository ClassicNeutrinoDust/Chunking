package com.chunking.engine.access;

/**
 * Public fast-path bridge implemented via Mixin on package-private {@code SurfaceRules$Condition}
 * implementations so flattened sequence and test rules can evaluate conditions without package-private
 * wrapper indirection.
 */
public interface SurfaceConditionAccess {
    boolean chunking$test();
}
