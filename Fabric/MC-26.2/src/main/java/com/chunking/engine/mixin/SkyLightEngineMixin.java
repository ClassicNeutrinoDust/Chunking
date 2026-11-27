package com.chunking.engine.mixin;

import com.chunking.engine.access.LightEngineAccess;
import com.chunking.engine.world.LightSpread;
import com.chunking.engine.access.LightUpdatingAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Fast sky-light propagation, cached chunk lookups, and heightmap-guided source seeding.
 */
@Mixin(SkyLightEngine.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class SkyLightEngineMixin extends LightEngine {
    protected SkyLightEngineMixin(LightChunkGetter chunkSource, LayerLightSectionStorage storage) {
        super(chunkSource, storage);
    }

    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutablePos;

    @Shadow
    @Final
    private ChunkSkyLightSources emptyChunkSources;

    @Shadow
    private int countEmptySectionsBelowIfAtBorder(long blockPos) {
        throw new AssertionError();
    }

    @Shadow
    private void propagateFromEmptySections(
            long blockPos,
            Direction direction,
            int level,
            boolean increase,
            int emptySectionsBelow
    ) {
        throw new AssertionError();
    }

    // Initialized in <init> (mixin field)
    @Unique
    private int[] chunking$centerSourcesY;

    @Unique
    private int[] chunking$northSourcesY;

    @Unique
    private int[] chunking$southSourcesY;

    @Unique
    private int[] chunking$westSourcesY;

    @Unique
    private int[] chunking$eastSourcesY;

    @Unique
    private int[] chunking$maxNeighborSourcesY;

    @Unique
    private LightSpread.EmptySectionsCallback chunking$skyEmptyCallback;

    @Unique
    private LightSpread.EmptySectionsCallback chunking$skyDecreaseEmptyCallback;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void chunking$initSkyScratch(CallbackInfo ci) {
        if (this.chunking$centerSourcesY == null) {
            this.chunking$centerSourcesY = new int[256];
            this.chunking$northSourcesY = new int[256];
            this.chunking$southSourcesY = new int[256];
            this.chunking$westSourcesY = new int[256];
            this.chunking$eastSourcesY = new int[256];
            this.chunking$maxNeighborSourcesY = new int[256];
            this.chunking$skyEmptyCallback = (nPos, dir, nextLevel, emptyBelow) ->
                    this.propagateFromEmptySections(nPos, dir, nextLevel, true, emptyBelow);
            this.chunking$skyDecreaseEmptyCallback = (nPos, dir, nextLevel, emptyBelow) ->
                    this.propagateFromEmptySections(nPos, dir, nextLevel, false, emptyBelow);
        }
    }

    /**
     * @author Chunking
     * @reason Route ChunkSkyLightSources lookup through LightEngine.getChunk's 64-slot cache instead of raw chunkSource.getChunkForLighting.
     */
    @Overwrite
    private ChunkSkyLightSources getChunkSources(int cx, int cz) {
        LightChunk chunk = ((LightEngineAccess) this).chunking$isInLightUpdate()
                ? this.getChunk(cx, cz)
                : this.chunkSource.getChunkForLighting(cx, cz);
        return chunk != null ? chunk.getSkyLightSources() : null;
    }

    /**
     * @author Chunking
     * @reason Single-lookup interior light propagation avoiding 6x MutableBlockPos, hash table, and BlockState dispatches.
     */
    @Overwrite
    protected void propagateIncrease(long blockPos, long queueEntry, int fromLevel) {
        int emptyBelow = this.countEmptySectionsBelowIfAtBorder(blockPos);
        LightSpread.propagateIncrease(
                (LightEngineAccess) this,
                this.mutablePos,
                blockPos,
                queueEntry,
                fromLevel,
                emptyBelow,
                this.chunking$skyEmptyCallback
        );
    }

    /**
     * @author Chunking
     * @reason Single-lookup interior sky-light decrease propagation avoiding 6x hash probes per node.
     */
    @Overwrite
    protected void propagateDecrease(long blockPos, long queueEntry) {
        int emptyBelow = this.countEmptySectionsBelowIfAtBorder(blockPos);
        LightSpread.propagateDecrease(
                (LightEngineAccess) this,
                this.mutablePos,
                blockPos,
                queueEntry,
                emptyBelow,
                this.chunking$skyDecreaseEmptyCallback,
                false
        );
    }

    /**
     * @author Chunking
     * @reason Precompute 256 column source heights once per chunk, preserve homogenous DataLayer(15) above terrain surface, write paired direct-nibble bytes on non-uniform sections, and skip non-enqueuing air voxels.
     */
    @Overwrite
    public void propagateLightSources(ChunkPos chunkPos) {
        int cx = chunkPos.x();
        int cz = chunkPos.z();
        long zeroNode = SectionPos.getZeroNode(cx, cz);
        super.setLightEnabled(chunkPos, true);

        ChunkSkyLightSources center = Objects.requireNonNullElse(this.getChunkSources(cx, cz), this.emptyChunkSources);
        ChunkSkyLightSources north = Objects.requireNonNullElse(this.getChunkSources(cx, cz - 1), this.emptyChunkSources);
        ChunkSkyLightSources south = Objects.requireNonNullElse(this.getChunkSources(cx, cz + 1), this.emptyChunkSources);
        ChunkSkyLightSources west = Objects.requireNonNullElse(this.getChunkSources(cx - 1, cz), this.emptyChunkSources);
        ChunkSkyLightSources east = Objects.requireNonNullElse(this.getChunkSources(cx + 1, cz), this.emptyChunkSources);

        int[] centerY = this.chunking$centerSourcesY;
        int[] northY = this.chunking$northSourcesY;
        int[] southY = this.chunking$southSourcesY;
        int[] westY = this.chunking$westSourcesY;
        int[] eastY = this.chunking$eastSourcesY;
        int[] maxNeighY = this.chunking$maxNeighborSourcesY;

        int maxCenterY = Integer.MIN_VALUE;
        for (int lz = 0; lz < 16; lz++) {
            int row = lz << 4;
            for (int lx = 0; lx < 16; lx++) {
                int cy = center.getLowestSourceY(lx, lz);
                centerY[row | lx] = cy;
                if (cy > maxCenterY) {
                    maxCenterY = cy;
                }
            }
        }

        int maxEnqueueY = maxCenterY;
        for (int lz = 0; lz < 16; lz++) {
            int row = lz << 4;
            int wBorder = west.getLowestSourceY(15, lz);
            int eBorder = east.getLowestSourceY(0, lz);
            for (int lx = 0; lx < 16; lx++) {
                int idx = row | lx;
                int ny = (lz == 0) ? north.getLowestSourceY(lx, 15) : centerY[((lz - 1) << 4) | lx];
                int sy = (lz == 15) ? south.getLowestSourceY(lx, 0) : centerY[((lz + 1) << 4) | lx];
                int wy = (lx == 0) ? wBorder : centerY[row | (lx - 1)];
                int ey = (lx == 15) ? eBorder : centerY[row | (lx + 1)];
                int mny = Math.max(Math.max(ny, sy), Math.max(wy, ey));
                northY[idx] = ny;
                southY[idx] = sy;
                westY[idx] = wy;
                eastY[idx] = ey;
                maxNeighY[idx] = mny;
                int enqTop = mny > Integer.MIN_VALUE ? mny - 1 : Integer.MIN_VALUE;
                if (enqTop > maxEnqueueY) {
                    maxEnqueueY = enqTop;
                }
            }
        }

        LightUpdatingAccess upd = (LightUpdatingAccess) this.storage;
        int topSectionY = upd.chunking$getUpdatingTopSectionY(zeroNode);
        int bottomSectionY = upd.chunking$getUpdatingBottomSectionY();

        int baseX = SectionPos.sectionToBlockCoord(cx);
        int baseZ = SectionPos.sectionToBlockCoord(cz);

        for (int sy = topSectionY - 1; sy >= bottomSectionY; sy--) {
            long secPos = SectionPos.asLong(cx, sy, cz);
            DataLayer existing = upd.chunking$getUpdatingLayer(secPos);
            if (existing == null) {
                continue;
            }
            int secMinY = SectionPos.sectionToBlockCoord(sy);
            int secMaxY = secMinY + 15;

            if (maxCenterY <= secMinY) {
                if (!existing.isDefinitelyFilledWith(15)) {
                    DataLayer writable = upd.chunking$getOrCopyWritableLayer(secPos);
                    writable.fill(15);
                }
                if (maxEnqueueY < secMinY) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    int row = lz << 4;
                    int wz = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int idx = row | lx;
                        int cy = centerY[idx];
                        int mny = maxNeighY[idx];
                        int mnyMinus1 = mny > Integer.MIN_VALUE ? mny - 1 : Integer.MIN_VALUE;
                        int topEnqY = Math.min(secMaxY, Math.max(cy, mnyMinus1));
                        if (topEnqY < secMinY) {
                            continue;
                        }
                        int ny = northY[idx];
                        int soy = southY[idx];
                        int wy = westY[idx];
                        int ey = eastY[idx];
                        int wx = baseX + lx;
                        for (int y = topEnqY; y >= secMinY; y--) {
                            this.enqueueIncrease(
                                    BlockPos.asLong(wx, y, wz),
                                    LightEngine.QueueEntry.increaseSkySourceInDirections(
                                            y == cy, y < ny, y < soy, y < wy, y < ey
                                    )
                            );
                        }
                    }
                }
                continue;
            }

            // Direct-nibble byte[] writes on non-uniform sections: DataLayer index = (ly << 8) | (lz << 4) | lx,
            // byte index = ((ly << 7) | (lz << 3) | (lx >> 1)), even lx -> low nibble (0x0F), odd lx -> high nibble (0xF0).
            byte[] data = null;
            boolean hasColumnsBelow = false;
            for (int lz = 0; lz < 16; lz++) {
                int row = lz << 4;
                int wz = baseZ + lz;
                int zTopByteBase = (15 << 7) | (lz << 3);
                for (int lx = 0; lx < 16; lx += 2) {
                    int idx0 = row | lx;
                    int idx1 = idx0 + 1;
                    int cy0 = centerY[idx0];
                    int cy1 = centerY[idx1];
                    boolean active0 = cy0 <= secMaxY;
                    boolean active1 = cy1 <= secMaxY;
                    if (!active0 && !active1) {
                        continue;
                    }
                    if (data == null) {
                        data = upd.chunking$getOrCopyWritableLayer(secPos).getData();
                    }
                    int stopY0 = active0 ? Math.max(secMinY, cy0) : (secMaxY + 1);
                    int stopY1 = active1 ? Math.max(secMinY, cy1) : (secMaxY + 1);
                    int bothStopY = Math.max(stopY0, stopY1);
                    int bIdx = zTopByteBase | (lx >> 1);
                    for (int y = secMaxY; y >= bothStopY; y--, bIdx -= 128) {
                        data[bIdx] = (byte) 0xFF;
                    }
                    if (stopY0 < bothStopY) {
                        for (int y = bothStopY - 1; y >= stopY0; y--, bIdx -= 128) {
                            data[bIdx] = (byte) (data[bIdx] | 0x0F);
                        }
                    } else if (stopY1 < bothStopY) {
                        for (int y = bothStopY - 1; y >= stopY1; y--, bIdx -= 128) {
                            data[bIdx] = (byte) (data[bIdx] | 0xF0);
                        }
                    }
                    if (active0) {
                        int mny0 = maxNeighY[idx0];
                        int mny0Minus1 = mny0 > Integer.MIN_VALUE ? mny0 - 1 : Integer.MIN_VALUE;
                        int topEnqY0 = Math.min(secMaxY, Math.max(cy0, mny0Minus1));
                        if (topEnqY0 >= stopY0) {
                            int ny = northY[idx0];
                            int soy = southY[idx0];
                            int wy = westY[idx0];
                            int ey = eastY[idx0];
                            int wx = baseX + lx;
                            for (int y = topEnqY0; y >= stopY0; y--) {
                                this.enqueueIncrease(
                                        BlockPos.asLong(wx, y, wz),
                                        LightEngine.QueueEntry.increaseSkySourceInDirections(
                                                y == cy0, y < ny, y < soy, y < wy, y < ey
                                        )
                                );
                            }
                        }
                        if (cy0 < secMinY) {
                            hasColumnsBelow = true;
                        }
                    }
                    if (active1) {
                        int mny1 = maxNeighY[idx1];
                        int mny1Minus1 = mny1 > Integer.MIN_VALUE ? mny1 - 1 : Integer.MIN_VALUE;
                        int topEnqY1 = Math.min(secMaxY, Math.max(cy1, mny1Minus1));
                        if (topEnqY1 >= stopY1) {
                            int ny = northY[idx1];
                            int soy = southY[idx1];
                            int wy = westY[idx1];
                            int ey = eastY[idx1];
                            int wx = baseX + lx + 1;
                            for (int y = topEnqY1; y >= stopY1; y--) {
                                this.enqueueIncrease(
                                        BlockPos.asLong(wx, y, wz),
                                        LightEngine.QueueEntry.increaseSkySourceInDirections(
                                                y == cy1, y < ny, y < soy, y < wy, y < ey
                                        )
                                );
                            }
                        }
                        if (cy1 < secMinY) {
                            hasColumnsBelow = true;
                        }
                    }
                }
            }
            if (!hasColumnsBelow) {
                break;
            }
        }
    }
}
