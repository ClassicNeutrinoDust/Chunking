package com.chunking.engine.access;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

public interface BeardifierAccess {
    @Nullable
    BoundingBox chunking$getAffectedBox();

    /**
     * Tight union AABB (including kernel radius) outside which this beardifier is guaranteed to return 0.0,
     * covering both vanilla pieces/junctions and modded extensions such as Moog's EnhancedBeardifierData.
     */
    @Nullable
    default BoundingBox chunking$getEffectiveUnionBox() {
        return chunking$getAffectedBox();
    }

    default boolean chunking$hasUnionBox() {
        return chunking$getEffectiveUnionBox() != null;
    }

    default int chunking$uMinX() {
        BoundingBox b = chunking$getEffectiveUnionBox();
        return b != null ? b.minX() : 0;
    }

    default int chunking$uMaxX() {
        BoundingBox b = chunking$getEffectiveUnionBox();
        return b != null ? b.maxX() : 0;
    }

    default int chunking$uMinY() {
        BoundingBox b = chunking$getEffectiveUnionBox();
        return b != null ? b.minY() : 0;
    }

    default int chunking$uMaxY() {
        BoundingBox b = chunking$getEffectiveUnionBox();
        return b != null ? b.maxY() : 0;
    }

    default int chunking$uMinZ() {
        BoundingBox b = chunking$getEffectiveUnionBox();
        return b != null ? b.minZ() : 0;
    }

    default int chunking$uMaxZ() {
        BoundingBox b = chunking$getEffectiveUnionBox();
        return b != null ? b.maxZ() : 0;
    }

    default boolean chunking$intersectsBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return chunking$hasUnionBox()
                && maxY >= chunking$uMinY() && minY <= chunking$uMaxY()
                && maxX >= chunking$uMinX() && minX <= chunking$uMaxX()
                && maxZ >= chunking$uMinZ() && minZ <= chunking$uMaxZ();
    }
}
