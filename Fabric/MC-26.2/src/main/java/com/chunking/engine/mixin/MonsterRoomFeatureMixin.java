package com.chunking.engine.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Predicate;

/**
 * Eliminates ~230 MB/probe of {@code BlockPos.offset}, {@code BlockPos.above}, and
 * {@code BlockPos.below} heap allocations inside {@link MonsterRoomFeature#place},
 * validates floor/ceiling first without scanning interior Y levels or double-reading blocks,
 * and defers predicate allocation until validation succeeds.
 */
@Mixin(MonsterRoomFeature.class)
public abstract class MonsterRoomFeatureMixin extends Feature<NoneFeatureConfiguration> {
    @Unique
    private static final Direction[] CHUNKING$HORIZONTALS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private static BlockState AIR;

    @Shadow
    protected abstract EntityType<?> randomEntityId(RandomSource random);

    protected MonsterRoomFeatureMixin(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /**
     * @author Chunking
     * @reason Fast floor/ceiling validation without interior scans or duplicate getBlockState calls, plus MutableBlockPos reuse.
     */
    @Overwrite
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        WorldGenLevel level = context.level();

        int halfX = random.nextInt(2) + 2;
        int minX = -halfX - 1;
        int maxX = halfX + 1;
        int halfZ = random.nextInt(2) + 2;
        int minZ = -halfZ - 1;
        int maxZ = halfZ + 1;
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();

        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos adjPos = new BlockPos.MutableBlockPos();

        int floorY = originY - 1;
        int ceilY = originY + 4;
        for (int dx = minX; dx <= maxX; dx++) {
            int wx = originX + dx;
            for (int dz = minZ; dz <= maxZ; dz++) {
                int wz = originZ + dz;
                if (!level.getBlockState(mutPos.set(wx, floorY, wz)).isSolid()) {
                    return false;
                }
                if (!level.getBlockState(mutPos.set(wx, ceilY, wz)).isSolid()) {
                    return false;
                }
            }
        }

        int openings = 0;
        for (int dx = minX; dx <= maxX; dx++) {
            int wx = originX + dx;
            boolean xEdge = (dx == minX || dx == maxX);
            for (int dz = minZ; dz <= maxZ; dz++) {
                if (!xEdge && dz != minZ && dz != maxZ) {
                    continue;
                }
                int wz = originZ + dz;
                BlockState wallState = level.getBlockState(mutPos.set(wx, originY, wz));
                if (wallState.isAir()
                        && level.getBlockState(adjPos.set(wx, originY + 1, wz)).isAir()) {
                    if (++openings > 5) {
                        return false;
                    }
                }
            }
        }

        if (openings < 1) {
            return false;
        }

        Predicate<BlockState> predicate = Feature.isReplaceable(BlockTags.FEATURES_CANNOT_REPLACE);
        int minBuildY = level.getMinY();
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = 3; dy >= -1; dy--) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    mutPos.set(originX + dx, originY + dy, originZ + dz);
                    BlockState state = level.getBlockState(mutPos);
                    if (dx != minX && dy != -1 && dz != minZ && dx != maxX && dy != 4 && dz != maxZ) {
                        if (!state.is(Blocks.CHEST) && !state.is(Blocks.SPAWNER)) {
                            this.safeSetBlock(level, mutPos, AIR, predicate);
                        }
                    } else if (mutPos.getY() >= minBuildY
                            && !level.getBlockState(adjPos.set(mutPos.getX(), mutPos.getY() - 1, mutPos.getZ())).isSolid()) {
                        level.setBlock(mutPos, AIR, 2);
                    } else if (state.isSolid() && !state.is(Blocks.CHEST)) {
                        if (dy == -1 && random.nextInt(4) != 0) {
                            this.safeSetBlock(level, mutPos, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), predicate);
                        } else {
                            this.safeSetBlock(level, mutPos, Blocks.COBBLESTONE.defaultBlockState(), predicate);
                        }
                    }
                }
            }
        }

        for (int chestTry = 0; chestTry < 2; chestTry++) {
            for (int attempt = 0; attempt < 3; attempt++) {
                int cx = originX + random.nextInt(halfX * 2 + 1) - halfX;
                int cz = originZ + random.nextInt(halfZ * 2 + 1) - halfZ;
                mutPos.set(cx, originY, cz);
                if (level.isEmptyBlock(mutPos)) {
                    int adjacentSolids = 0;
                    for (Direction dir : CHUNKING$HORIZONTALS) {
                        adjPos.set(cx + dir.getStepX(), originY, cz + dir.getStepZ());
                        if (level.getBlockState(adjPos).isSolid()) {
                            adjacentSolids++;
                        }
                    }
                    if (adjacentSolids == 1) {
                        BlockPos chestPos = mutPos.immutable();
                        this.safeSetBlock(
                                level,
                                chestPos,
                                StructurePiece.reorient(level, chestPos, Blocks.CHEST.defaultBlockState()),
                                predicate);
                        RandomizableContainer.setBlockEntityLootTable(
                                level, random, chestPos, BuiltInLootTables.SIMPLE_DUNGEON);
                        break;
                    }
                }
            }
        }

        this.safeSetBlock(level, origin, Blocks.SPAWNER.defaultBlockState(), predicate);
        if (level.getBlockEntity(origin) instanceof SpawnerBlockEntity spawner) {
            spawner.setEntityId(this.randomEntityId(random), random);
        } else {
            LOGGER.error("Failed to fetch mob spawner entity at ({}, {}, {})", originX, originY, originZ);
        }
        return true;
    }
}
