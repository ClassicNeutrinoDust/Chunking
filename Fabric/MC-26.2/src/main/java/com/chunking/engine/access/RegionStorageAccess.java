package com.chunking.engine.access;

/**
 * Duck interface on {@code RegionFileStorage} to check in O(1) time whether a region file
 * ({@code r.X.Z.mca}) existed on disk prior to the current runtime session, avoiding 1,024-chunk
 * {@code IOWorker.createOldDataForRegion} scans on newly explored regions.
 */
public interface RegionStorageAccess {
    boolean chunking$hasPreExistingRegionFile(int regionX, int regionZ);

    default boolean chunking$hasRegionFileOnDisk(int regionX, int regionZ) {
        return chunking$hasPreExistingRegionFile(regionX, regionZ);
    }
}
