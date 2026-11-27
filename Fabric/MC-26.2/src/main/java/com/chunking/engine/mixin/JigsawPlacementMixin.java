package com.chunking.engine.mixin;

import com.chunking.engine.world.BoxTree;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * Replaces vanilla's O(N^2) VoxelShape BitSet merges in {@code JigsawPlacement.Placer} with
 * {@link BoxTree} while preserving exact RNG draw order and piece layout parity.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacementMixin {
    // Initialized in <init> (mixin field)
    @Unique
    private IdentityHashMap<VoxelShape, BoxTree> chunking$octrees;
    @Unique
    private BoxTree chunking$activeParentOctree;
    @Unique
    private AABB chunking$pendingQuery;
    @Unique
    private AABB chunking$pendingAdd;
    @Unique
    private boolean chunking$connectorOutOfBoundsXZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initOctreeMap(CallbackInfo ci) {
        this.chunking$octrees = new IdentityHashMap<>(8);
    }

    @Inject(method = "tryPlacingChildren", at = @At("HEAD"))
    private void chunking$prepareOctree(
            PoolElementStructurePiece piece,
            MutableObject<VoxelShape> free,
            int depth,
            boolean useExpansionHack,
            LevelHeightAccessor level,
            RandomState randomState,
            PoolAliasLookup aliasLookup,
            LiquidSettings liquidSettings,
            CallbackInfo ci
    ) {
        this.chunking$pendingQuery = null;
        this.chunking$pendingAdd = null;
        this.chunking$connectorOutOfBoundsXZ = false;

        VoxelShape rootShape = free.get();
        if (rootShape == null) {
            this.chunking$activeParentOctree = null;
            return;
        }
        BoxTree existing = this.chunking$octrees.get(rootShape);
        if (existing != null) {
            this.chunking$activeParentOctree = existing;
            return;
        }
        if (depth != 0) {
            this.chunking$activeParentOctree = null;
            return;
        }
        if (rootShape.isEmpty()) {
            BoxTree empty = new BoxTree(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
            this.chunking$octrees.put(rootShape, empty);
            this.chunking$activeParentOctree = empty;
            return;
        }
        // Verify rootShape == outer \ startBox once so custom modded VoxelShapes fall back cleanly.
        AABB outer = rootShape.bounds();
        AABB startAabb = AABB.of(piece.getBoundingBox());
        VoxelShape expected = Shapes.join(Shapes.create(outer), Shapes.create(startAabb), BooleanOp.ONLY_FIRST);
        if (Shapes.joinIsNotEmpty(rootShape, expected, BooleanOp.NOT_SAME)) {
            this.chunking$activeParentOctree = null;
            return;
        }
        BoxTree octree = new BoxTree(outer);
        octree.add(startAabb);
        this.chunking$octrees.put(rootShape, octree);
        this.chunking$activeParentOctree = octree;
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;isInside(Lnet/minecraft/core/Vec3i;)Z"
            )
    )
    private boolean chunking$checkConnectorBounds(
            BoundingBox pieceBox,
            Vec3i attachPos,
            Operation<Boolean> original
    ) {
        boolean inside = original.call(pieceBox, attachPos);
        BoxTree parent = this.chunking$activeParentOctree;
        if (!inside && parent != null) {
            this.chunking$connectorOutOfBoundsXZ = !parent.withinBoundsXZ(
                    attachPos.getX() + 0.25D, attachPos.getX() + 0.75D,
                    attachPos.getZ() + 0.25D, attachPos.getZ() + 0.75D
            );
        } else {
            this.chunking$connectorOutOfBoundsXZ = false;
        }
        return inside;
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;stream()Ljava/util/stream/Stream;"
            )
    )
    private Stream<?> chunking$skipExpansionStreamIfOutOfBounds(List<?> list, Operation<Stream<?>> original) {
        if (this.chunking$connectorOutOfBoundsXZ) {
            return Stream.empty();
        }
        return original.call(list);
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/JigsawBlock;canAttach(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$JigsawBlockInfo;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$JigsawBlockInfo;)Z"
            )
    )
    private boolean chunking$skipBlockedAttach(
            StructureTemplate.JigsawBlockInfo parentInfo,
            StructureTemplate.JigsawBlockInfo childInfo,
            Operation<Boolean> original
    ) {
        if (this.chunking$connectorOutOfBoundsXZ) {
            return false;
        }
        return original.call(parentInfo, childInfo);
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    ordinal = 0
            )
    )
    private VoxelShape chunking$createChildOctree(AABB pieceAabb, Operation<VoxelShape> original) {
        VoxelShape token = original.call(pieceAabb);
        if (token != Shapes.block() && token != Shapes.empty() && !this.chunking$octrees.containsKey(token)) {
            this.chunking$octrees.put(token, new BoxTree(pieceAabb));
        }
        return token;
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    ordinal = 1
            )
    )
    private VoxelShape chunking$captureQueryBox(AABB deflatedBox, Operation<VoxelShape> original) {
        if (this.chunking$octrees.isEmpty()) {
            return original.call(deflatedBox);
        }
        this.chunking$pendingQuery = deflatedBox;
        return Shapes.empty();
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"
            )
    )
    private boolean chunking$queryOctree(
            VoxelShape freeShape,
            VoxelShape candidateShape,
            BooleanOp op,
            Operation<Boolean> original
    ) {
        AABB query = this.chunking$pendingQuery;
        this.chunking$pendingQuery = null;
        BoxTree octree = this.chunking$octrees.get(freeShape);
        if (octree != null && query != null) {
            return !octree.withinBounds(query) || octree.intersects(query);
        }
        VoxelShape actualSecond = (candidateShape == Shapes.empty() && query != null)
                ? Shapes.create(query)
                : candidateShape;
        return original.call(freeShape, actualSecond, op);
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    ordinal = 2
            )
    )
    private VoxelShape chunking$captureAddBox(AABB placedBox, Operation<VoxelShape> original) {
        if (this.chunking$octrees.isEmpty()) {
            return original.call(placedBox);
        }
        this.chunking$pendingAdd = placedBox;
        return Shapes.empty();
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape chunking$addToOctree(
            VoxelShape freeShape,
            VoxelShape placedShape,
            BooleanOp op,
            Operation<VoxelShape> original
    ) {
        AABB added = this.chunking$pendingAdd;
        this.chunking$pendingAdd = null;
        BoxTree octree = this.chunking$octrees.get(freeShape);
        if (octree != null && added != null) {
            octree.add(added);
            // Keep the same VoxelShape key in MutableObject so subsequent pieces resolve the same octree.
            return freeShape;
        }
        VoxelShape actualSecond = (placedShape == Shapes.empty() && added != null)
                ? Shapes.create(added)
                : placedShape;
        return original.call(freeShape, actualSecond, op);
    }
}
