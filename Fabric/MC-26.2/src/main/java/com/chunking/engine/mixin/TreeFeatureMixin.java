package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.world.TreeLeafScratch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Eliminates per-tree 7x {@code HashSet<BlockPos>}, {@code Sets.union}, {@code Direction.values()}
 * array clones, and per-leaf {@code BlockPos.immutable()} allocations inside {@link TreeFeature#updateLeaves},
 * and avoids {@code Predicate} dispatch in {@code validTreePos}/{@code isAirOrLeaves}/{@code isVine}.
 */
@Mixin(TreeFeature.class)
public abstract class TreeFeatureMixin {
    @Unique
    private static final int CHUNKING$MAX_LEAF_VOLUME = 131_072;

    @Unique
    private static final Direction[] CHUNKING$DIRS = Direction.values();

    @Inject(method = "isVine", at = @At("HEAD"), cancellable = true)
    private static void chunking$fastIsVine(
            LevelSimulatedReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof BlockGetter bg) {
            cir.setReturnValue(bg.getBlockState(pos).is(Blocks.VINE));
        }
    }

    @Inject(method = "isAirOrLeaves", at = @At("HEAD"), cancellable = true)
    private static void chunking$fastIsAirOrLeaves(
            LevelSimulatedReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof BlockGetter bg) {
            BlockState state = bg.getBlockState(pos);
            cir.setReturnValue(state.isAir() || state.is(BlockTags.LEAVES));
        }
    }

    @Inject(method = "validTreePos", at = @At("HEAD"), cancellable = true)
    private static void chunking$fastValidTreePos(
            LevelSimulatedReader level,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (level instanceof BlockGetter bg) {
            BlockState state = bg.getBlockState(pos);
            cir.setReturnValue(state.isAir() || state.is(BlockTags.REPLACEABLE_BY_TREES));
        }
    }

    @Inject(method = "updateLeaves", at = @At("HEAD"), cancellable = true)
    private static void chunking$fastUpdateLeaves(
            LevelAccessor level,
            BoundingBox box,
            Set<BlockPos> logs,
            Set<BlockPos> decorations,
            Set<BlockPos> roots,
            CallbackInfoReturnable<DiscreteVoxelShape> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return;
        }
        int xSpan = box.getXSpan();
        int ySpan = box.getYSpan();
        int zSpan = box.getZSpan();
        if (xSpan <= 0 || ySpan <= 0 || zSpan <= 0 || xSpan > 1024 || ySpan > 1024 || zSpan > 1024) {
            return;
        }
        long volLong = (long) xSpan * (long) ySpan * (long) zSpan;
        if (volLong > CHUNKING$MAX_LEAF_VOLUME) {
            return;
        }
        int vol = (int) volLong;
        int minX = box.minX();
        int minY = box.minY();
        int minZ = box.minZ();

        DiscreteVoxelShape shape = new BitSetDiscreteVoxelShape(xSpan, ySpan, zSpan);
        for (BlockPos pos : decorations) {
            if (box.isInside(pos)) {
                shape.fill(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ);
            }
        }
        for (BlockPos pos : roots) {
            if (box.isInside(pos)) {
                shape.fill(pos.getX() - minX, pos.getY() - minY, pos.getZ() - minZ);
            }
        }

        TreeLeafScratch scratch = TreeLeafScratch.acquire();
        try {
            Arrays.fill(scratch.sizes, 0);
            byte[] queuedMask = scratch.queuedMask;
            if (queuedMask.length < vol) {
                queuedMask = new byte[Math.max(vol, queuedMask.length << 1)];
                scratch.queuedMask = queuedMask;
            }
            Arrays.fill(queuedMask, 0, vol, (byte) 0);

            for (BlockPos pos : logs) {
                if (!box.isInside(pos)) {
                    continue;
                }
                int rx = pos.getX() - minX;
                int ry = pos.getY() - minY;
                int rz = pos.getZ() - minZ;
                int idx = (ry * zSpan + rz) * xSpan + rx;
                if ((queuedMask[idx] & 1) == 0) {
                    queuedMask[idx] |= 1;
                    scratch.push(0, (ry << 20) | (rz << 10) | rx);
                }
            }

            BlockPos.MutableBlockPos curPos = scratch.curPos;
            BlockPos.MutableBlockPos nbPos = scratch.nbPos;
            int dist = 0;
            while (true) {
                while (dist < 7 && scratch.sizes[dist] == 0) {
                    dist++;
                }
                if (dist >= 7) {
                    break;
                }
                int sz = --scratch.sizes[dist];
                int packed = scratch.buckets[dist][sz];
                int rx = packed & 1023;
                int rz = (packed >>> 10) & 1023;
                int ry = packed >>> 20;
                if (dist != 0 && shape.isFull(rx, ry, rz)) {
                    continue;
                }

                int wx = minX + rx;
                int wy = minY + ry;
                int wz = minZ + rz;
                if (dist != 0) {
                    curPos.set(wx, wy, wz);
                    BlockState state = level.getBlockState(curPos);
                    BlockState updated = state.setValue(BlockStateProperties.DISTANCE, dist);
                    if (updated != state) {
                        level.setBlock(level instanceof WorldGenRegion ? curPos : curPos.immutable(), updated, 19);
                    }
                }
                shape.fill(rx, ry, rz);

                for (int d = 0; d < CHUNKING$DIRS.length; d++) {
                    Direction dir = CHUNKING$DIRS[d];
                    int nrx = rx + dir.getStepX();
                    int nry = ry + dir.getStepY();
                    int nrz = rz + dir.getStepZ();
                    if (nrx < 0 || nrx >= xSpan || nry < 0 || nry >= ySpan || nrz < 0 || nrz >= zSpan) {
                        continue;
                    }
                    if (shape.isFull(nrx, nry, nrz)) {
                        continue;
                    }
                    nbPos.set(minX + nrx, minY + nry, minZ + nrz);
                    BlockState ns = level.getBlockState(nbPos);
                    if (ns.isAir()) {
                        continue;
                    }
                    OptionalInt opt = LeavesBlock.getOptionalDistanceAt(ns);
                    if (opt.isEmpty()) {
                        continue;
                    }
                    int optDist = opt.getAsInt();
                    int newDist = Math.min(optDist, dist + 1);
                    if (newDist < 7) {
                        int nIdx = (nry * zSpan + nrz) * xSpan + nrx;
                        int bit = 1 << newDist;
                        if ((queuedMask[nIdx] & bit) == 0) {
                            queuedMask[nIdx] |= (byte) bit;
                            scratch.push(newDist, (nry << 20) | (nrz << 10) | nrx);
                        }
                        if (newDist < dist) {
                            dist = newDist;
                        }
                    }
                }
            }
        } finally {
            scratch.inUse = false;
        }
        cir.setReturnValue(shape);
    }
}
