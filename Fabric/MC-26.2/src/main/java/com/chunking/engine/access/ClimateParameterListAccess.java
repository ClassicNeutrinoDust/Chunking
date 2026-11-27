package com.chunking.engine.access;

/**
 * Exposes the R-tree index of {@code Climate$ParameterList} without package-protected visibility issues.
 */
public interface ClimateParameterListAccess<T> {
    ClimateRTreeAccess<T> chunking$index();
}
