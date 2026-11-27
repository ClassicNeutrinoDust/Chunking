package com.chunking.engine.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import com.chunking.engine.access.LightEngineAccess;
import com.chunking.engine.access.LightUpdatingAccess;

/**
 * Shared fast-path for {@code SkyLightEngine.propagateIncrease} and {@code BlockLightEngine.propagateIncrease}.
 *
 * <p>When a light propagation node lies strictly in a section interior ({@code lx,ly,lz in 1..14}),
 * all 6 neighbors live in the same section and chunk. A single {@code updatingSectionData} lookup,
 * single {@link LevelChunkSection} lookup, and direct {@code DataLayer} nibble checks eliminate 6x
 * hash probes, 6x {@code MutableBlockPos} mutations, and {@code section.hasOnlyAir()} state fetches.
 */
public final class LightSpread {
    public interface EmptySectionsCallback {
        void propagate(long nPos, Direction dir, int nextLevel, int emptyBelow);
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    private LightSpread() {}

    public static boolean isEmptyShapeFast(BlockState state) {
        return !state.canOcclude() || !state.useShapeForLightOcclusion();
    }

    public static void propagateIncrease(
            LightEngineAccess engine,
            BlockPos.MutableBlockPos mutablePos,
            long blockPos,
            long queueEntry,
            int fromLevel,
            int emptyBelow,
            EmptySectionsCallback skyCallback
    ) {
        int bx = BlockPos.getX(blockPos);
        int by = BlockPos.getY(blockPos);
        int bz = BlockPos.getZ(blockPos);
        int lx = SectionPos.sectionRelative(bx);
        int ly = SectionPos.sectionRelative(by);
        int lz = SectionPos.sectionRelative(bz);

        LightUpdatingAccess upd = engine.chunking$getStorage();
        boolean fromEmpty = LightEngine.QueueEntry.isFromEmptyShape(queueEntry);

        if (lx > 0 && lx < 15 && ly > 0 && ly < 15 && lz > 0 && lz < 15) {
            int cx = bx >> 4;
            int sy = by >> 4;
            int cz = bz >> 4;
            long secPos = SectionPos.asLong(cx, sy, cz);
            DataLayer dl = upd.chunking$getUpdatingLayer(secPos);
            if (dl == null) {
                return;
            }
            LightChunk lc = engine.chunking$getChunk(cx, cz);
            if (lc == null) {
                return;
            }
            if (lc instanceof ChunkAccess ca && !(ca instanceof LevelChunk dbg && dbg.getLevel().isDebug())) {
                int secIdx = ca.getSectionIndexFromSectionY(sy);
                LevelChunkSection[] secs = ca.getSections();
                if (secIdx >= 0 && secIdx < secs.length && secs[secIdx] != null) {
                    LevelChunkSection sec = secs[secIdx];
                    boolean onlyAir = sec.hasOnlyAir();
                    boolean copied = false;
                    BlockState fromState = null;

                    if (onlyAir && fromEmpty) {
                        int next = fromLevel - 1;
                        for (int i = 0; i < DIRECTIONS.length; i++) {
                            Direction dir = DIRECTIONS[i];
                            if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir)) {
                                continue;
                            }
                            int nlx = lx + dir.getStepX();
                            int nly = ly + dir.getStepY();
                            int nlz = lz + dir.getStepZ();
                            if (next <= dl.get(nlx, nly, nlz)) {
                                continue;
                            }
                            if (!copied) {
                                dl = upd.chunking$getOrCopyWritableLayer(secPos);
                                copied = true;
                            }
                            dl.set(nlx, nly, nlz, next);
                            if (nlx == 0 || nlx == 15 || nly == 0 || nly == 15 || nlz == 0 || nlz == 15) {
                                upd.chunking$markBorderAffected(bx + dir.getStepX(), by + dir.getStepY(), bz + dir.getStepZ());
                            }
                            if (next > 1) {
                                long nPos = BlockPos.offset(blockPos, dir);
                                engine.chunking$enqueueIncrease(
                                        nPos,
                                        LightEngine.QueueEntry.increaseSkipOneDirection(next, true, dir.getOpposite())
                                );
                            }
                        }
                        return;
                    }

                    BlockState air = Blocks.AIR.defaultBlockState();
                    for (int i = 0; i < DIRECTIONS.length; i++) {
                        Direction dir = DIRECTIONS[i];
                        if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir)) {
                            continue;
                        }
                        int nlx = lx + dir.getStepX();
                        int nly = ly + dir.getStepY();
                        int nlz = lz + dir.getStepZ();
                        int cur = dl.get(nlx, nly, nlz);
                        if (fromLevel - 1 <= cur) {
                            continue;
                        }
                        BlockState toState = onlyAir ? air : sec.getBlockState(nlx, nly, nlz);
                        int next = fromLevel - engine.chunking$getOpacity(toState);
                        if (next <= cur) {
                            continue;
                        }
                        if (fromState == null) {
                            fromState = (fromEmpty || onlyAir) ? air : sec.getBlockState(lx, ly, lz);
                        }
                        if (engine.chunking$shapeOccludes(fromState, toState, dir)) {
                            continue;
                        }
                        if (!copied) {
                            dl = upd.chunking$getOrCopyWritableLayer(secPos);
                            copied = true;
                        }
                        dl.set(nlx, nly, nlz, next);
                        if (nlx == 0 || nlx == 15 || nly == 0 || nly == 15 || nlz == 0 || nlz == 15) {
                            upd.chunking$markBorderAffected(bx + dir.getStepX(), by + dir.getStepY(), bz + dir.getStepZ());
                        }
                        if (next > 1) {
                            long nPos = BlockPos.offset(blockPos, dir);
                            engine.chunking$enqueueIncrease(
                                    nPos,
                                    LightEngine.QueueEntry.increaseSkipOneDirection(next, isEmptyShapeFast(toState), dir.getOpposite())
                            );
                        }
                    }
                    return;
                }
            }
        }

        BlockState fromState = null;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            Direction dir = DIRECTIONS[i];
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir)) {
                continue;
            }
            int nbx = bx + dir.getStepX();
            int nby = by + dir.getStepY();
            int nbz = bz + dir.getStepZ();
            long nSec = SectionPos.asLong(nbx >> 4, nby >> 4, nbz >> 4);
            DataLayer nDl = upd.chunking$getUpdatingLayer(nSec);
            if (nDl == null) {
                continue;
            }
            int nlx = nbx & 15;
            int nly = nby & 15;
            int nlz = nbz & 15;
            int cur = nDl.get(nlx, nly, nlz);
            if (fromLevel - 1 <= cur) {
                continue;
            }
            BlockState toState = engine.chunking$getState(nbx, nby, nbz, mutablePos);
            int next = fromLevel - engine.chunking$getOpacity(toState);
            if (next <= cur) {
                continue;
            }
            if (fromState == null) {
                fromState = fromEmpty
                        ? Blocks.AIR.defaultBlockState()
                        : engine.chunking$getState(bx, by, bz, mutablePos);
            }
            if (engine.chunking$shapeOccludes(fromState, toState, dir)) {
                continue;
            }
            setStoredLevelDirect(upd, nbx, nby, nbz, nSec, nlx, nly, nlz, next);
            long nPos = BlockPos.asLong(nbx, nby, nbz);
            if (next > 1) {
                engine.chunking$enqueueIncrease(
                        nPos,
                        LightEngine.QueueEntry.increaseSkipOneDirection(next, isEmptyShapeFast(toState), dir.getOpposite())
                );
            }
            if (skyCallback != null && emptyBelow > 0) {
                skyCallback.propagate(nPos, dir, next, emptyBelow);
            }
        }
    }

    public static void propagateDecrease(
            LightEngineAccess engine,
            BlockPos.MutableBlockPos mutablePos,
            long blockPos,
            long queueEntry,
            int emptyBelow,
            EmptySectionsCallback skyDecreaseCallback,
            boolean isBlockLight
    ) {
        int fromLevel = LightEngine.QueueEntry.getFromLevel(queueEntry);
        int bx = BlockPos.getX(blockPos);
        int by = BlockPos.getY(blockPos);
        int bz = BlockPos.getZ(blockPos);
        int lx = SectionPos.sectionRelative(bx);
        int ly = SectionPos.sectionRelative(by);
        int lz = SectionPos.sectionRelative(bz);

        LightUpdatingAccess upd = engine.chunking$getStorage();

        if (lx > 0 && lx < 15 && ly > 0 && ly < 15 && lz > 0 && lz < 15) {
            int cx = bx >> 4;
            int sy = by >> 4;
            int cz = bz >> 4;
            long secPos = SectionPos.asLong(cx, sy, cz);
            DataLayer dl = upd.chunking$getUpdatingLayer(secPos);
            if (dl == null || dl.isEmpty()) {
                return;
            }
            LevelChunkSection blockSec = null;
            boolean blockSecOnlyAir = false;
            boolean blockSecResolved = false;
            BlockState airState = null;
            boolean copied = false;
            for (int i = 0; i < DIRECTIONS.length; i++) {
                Direction dir = DIRECTIONS[i];
                if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir)) {
                    continue;
                }
                int nlx = lx + dir.getStepX();
                int nly = ly + dir.getStepY();
                int nlz = lz + dir.getStepZ();
                int neighborLevel = dl.get(nlx, nly, nlz);
                if (neighborLevel == 0) {
                    continue;
                }
                int nbx = bx + dir.getStepX();
                int nby = by + dir.getStepY();
                int nbz = bz + dir.getStepZ();
                long nPos = BlockPos.asLong(nbx, nby, nbz);
                if (neighborLevel <= fromLevel - 1) {
                    if (!isBlockLight) {
                        if (!copied) {
                            dl = upd.chunking$getOrCopyWritableLayer(secPos);
                            copied = true;
                        }
                        dl.set(nlx, nly, nlz, 0);
                        if (nlx == 0 || nlx == 15 || nly == 0 || nly == 15 || nlz == 0 || nlz == 15) {
                            upd.chunking$markBorderAffected(nbx, nby, nbz);
                        }
                        engine.chunking$enqueueDecrease(
                                nPos,
                                LightEngine.QueueEntry.decreaseSkipOneDirection(neighborLevel, dir.getOpposite())
                        );
                    } else {
                        if (!blockSecResolved) {
                            blockSecResolved = true;
                            LightChunk lc = engine.chunking$getChunk(cx, cz);
                            if (lc instanceof ChunkAccess ca && !(ca instanceof LevelChunk dbg && dbg.getLevel().isDebug())) {
                                int secIdx = ca.getSectionIndexFromSectionY(sy);
                                LevelChunkSection[] secs = ca.getSections();
                                if (secIdx >= 0 && secIdx < secs.length && secs[secIdx] != null) {
                                    blockSec = secs[secIdx];
                                    blockSecOnlyAir = blockSec.hasOnlyAir();
                                    if (blockSecOnlyAir) {
                                        airState = Blocks.AIR.defaultBlockState();
                                    }
                                }
                            }
                        }
                        BlockState toState = blockSec != null
                                ? (blockSecOnlyAir ? airState : blockSec.getBlockState(nlx, nly, nlz))
                                : engine.chunking$getState(nbx, nby, nbz, mutablePos);
                        int emission = engine.chunking$getEmission(nPos, toState);
                        if (!copied) {
                            dl = upd.chunking$getOrCopyWritableLayer(secPos);
                            copied = true;
                        }
                        dl.set(nlx, nly, nlz, 0);
                        if (nlx == 0 || nlx == 15 || nly == 0 || nly == 15 || nlz == 0 || nlz == 15) {
                            upd.chunking$markBorderAffected(nbx, nby, nbz);
                        }
                        if (emission < neighborLevel) {
                            engine.chunking$enqueueDecrease(
                                    nPos,
                                    LightEngine.QueueEntry.decreaseSkipOneDirection(neighborLevel, dir.getOpposite())
                            );
                        }
                        if (emission > 0) {
                            engine.chunking$enqueueIncrease(
                                    nPos,
                                    LightEngine.QueueEntry.increaseLightFromEmission(emission, isEmptyShapeFast(toState))
                            );
                        }
                    }
                } else {
                    engine.chunking$enqueueIncrease(
                            nPos,
                            LightEngine.QueueEntry.increaseOnlyOneDirection(neighborLevel, false, dir.getOpposite())
                    );
                }
            }
            return;
        }

        for (int i = 0; i < DIRECTIONS.length; i++) {
            Direction dir = DIRECTIONS[i];
            if (!LightEngine.QueueEntry.shouldPropagateInDirection(queueEntry, dir)) {
                continue;
            }
            int nbx = bx + dir.getStepX();
            int nby = by + dir.getStepY();
            int nbz = bz + dir.getStepZ();
            long nSec = SectionPos.asLong(nbx >> 4, nby >> 4, nbz >> 4);
            DataLayer nDl = upd.chunking$getUpdatingLayer(nSec);
            if (nDl == null) {
                continue;
            }
            int nlx = nbx & 15;
            int nly = nby & 15;
            int nlz = nbz & 15;
            int neighborLevel = nDl.get(nlx, nly, nlz);
            if (neighborLevel == 0) {
                continue;
            }
            long nPos = BlockPos.asLong(nbx, nby, nbz);
            if (neighborLevel <= fromLevel - 1) {
                if (!isBlockLight) {
                    setStoredLevelDirect(upd, nbx, nby, nbz, nSec, nlx, nly, nlz, 0);
                    engine.chunking$enqueueDecrease(
                            nPos,
                            LightEngine.QueueEntry.decreaseSkipOneDirection(neighborLevel, dir.getOpposite())
                    );
                    if (skyDecreaseCallback != null && emptyBelow > 0) {
                        skyDecreaseCallback.propagate(nPos, dir, neighborLevel, emptyBelow);
                    }
                } else {
                    BlockState toState = engine.chunking$getState(nbx, nby, nbz, mutablePos);
                    int emission = engine.chunking$getEmission(nPos, toState);
                    setStoredLevelDirect(upd, nbx, nby, nbz, nSec, nlx, nly, nlz, 0);
                    if (emission < neighborLevel) {
                        engine.chunking$enqueueDecrease(
                                nPos,
                                LightEngine.QueueEntry.decreaseSkipOneDirection(neighborLevel, dir.getOpposite())
                        );
                    }
                    if (emission > 0) {
                        engine.chunking$enqueueIncrease(
                                nPos,
                                LightEngine.QueueEntry.increaseLightFromEmission(emission, isEmptyShapeFast(toState))
                        );
                    }
                }
            } else {
                engine.chunking$enqueueIncrease(
                        nPos,
                        LightEngine.QueueEntry.increaseOnlyOneDirection(neighborLevel, false, dir.getOpposite())
                );
            }
        }
    }

    private static void setStoredLevelDirect(
            LightUpdatingAccess upd,
            int bx,
            int by,
            int bz,
            long secPos,
            int lx,
            int ly,
            int lz,
            int level
    ) {
        DataLayer dl = upd.chunking$getOrCopyWritableLayer(secPos);
        dl.set(lx, ly, lz, level);
        if (lx == 0 || lx == 15 || ly == 0 || ly == 15 || lz == 0 || lz == 15) {
            upd.chunking$markBorderAffected(bx, by, bz);
        }
    }
}
