package com.chunking.engine.mixin;

import com.chunking.engine.access.LightEngineAccess;
import com.chunking.engine.world.LightSpread;
import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Fast block-light propagation and palette-based section source scanning.
 */
@Mixin(BlockLightEngine.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class BlockLightEngineMixin extends LightEngine implements LightEngineAccess {
    protected BlockLightEngineMixin(LightChunkGetter chunkSource, LayerLightSectionStorage storage) {
        super(chunkSource, storage);
    }

    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutablePos;

    @Shadow
    private int getEmission(long blockPos, BlockState state) {
        throw new AssertionError();
    }

    @Override
    public int chunking$getEmission(long blockPos, BlockState state) {
        return this.getEmission(blockPos, state);
    }

    /**
     * @author Chunking
     * @reason Single-lookup interior block-light propagation avoiding 6x MutableBlockPos, hash table, and BlockState dispatches.
     */
    @Overwrite
    protected void propagateIncrease(long blockPos, long queueEntry, int fromLevel) {
        LightSpread.propagateIncrease(
                (LightEngineAccess) this,
                this.mutablePos,
                blockPos,
                queueEntry,
                fromLevel,
                0,
                null
        );
    }

    /**
     * @author Chunking
     * @reason Single-lookup interior block-light decrease propagation avoiding 6x hash probes per node.
     */
    @Overwrite
    protected void propagateDecrease(long blockPos, long queueEntry) {
        LightSpread.propagateDecrease(
                (LightEngineAccess) this,
                this.mutablePos,
                blockPos,
                queueEntry,
                0,
                null,
                true
        );
    }

    @Unique
    private static final BlockState CHUNKING$AIR = Blocks.AIR.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    @Unique
    private static final boolean CHUNKING$VANILLA_AIR_NON_EMISSIVE =
            CHUNKING$AIR.getLightEmission() == 0
                    && CHUNKING$CAVE_AIR.getLightEmission() == 0
                    && CHUNKING$VOID_AIR.getLightEmission() == 0;

    @Unique
    private static final boolean CHUNKING$ANY_EMISSIVE_AIR = chunking$detectEmissiveAir();

    @Unique
    private static boolean chunking$detectEmissiveAir() {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof AirBlock) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    if (state.getLightEmission() > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Unique
    private void chunking$enqueueVoxel(int baseX, int baseY, int baseZ, int idx, BlockState state, int emission) {
        this.enqueueIncrease(
                BlockPos.asLong(baseX + (idx & 15), baseY + ((idx >> 8) & 15), baseZ + ((idx >> 4) & 15)),
                LightEngine.QueueEntry.increaseLightFromEmission(
                        emission,
                        LightSpread.isEmptyShapeFast(state)
                )
        );
    }

    @Unique
    private boolean chunking$scanSectionData(
            PalettedDataAccess<BlockState> data,
            int baseX,
            int baseY,
            int baseZ
    ) {
        Palette<BlockState> palette = data.chunking$palette();
        BitStorage bitStorage = data.chunking$storage();
        int palSize = palette != null ? palette.getSize() : 0;
        if (bitStorage instanceof ZeroBitStorage) {
            if (palSize <= 0) {
                return true;
            }
            BlockState s = palette.valueFor(0);
            int emission = s != null ? s.getLightEmission() : 0;
            if (emission == 0) {
                return true;
            }
            long entry = LightEngine.QueueEntry.increaseLightFromEmission(
                    emission,
                    LightSpread.isEmptyShapeFast(s)
            );
            for (int ly = 0; ly < 16; ly++) {
                int wy = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int wz = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        this.enqueueIncrease(BlockPos.asLong(baseX + lx, wy, wz), entry);
                    }
                }
            }
            return true;
        }
        if (palSize <= 0 || bitStorage == null) {
            return false;
        }
        long emissiveMask = 0L;
        boolean[] emissiveFlags = null;
        boolean zeroEmissive;
        if (palSize <= 64) {
            for (int p = 0; p < palSize; p++) {
                BlockState s = palette.valueFor(p);
                if (s != null && s.getLightEmission() != 0) {
                    emissiveMask |= (1L << p);
                }
            }
            if (emissiveMask == 0L) {
                return true;
            }
            zeroEmissive = (emissiveMask & 1L) != 0L;
        } else if (palSize <= 512) {
            emissiveFlags = new boolean[palSize];
            boolean anyEmissive = false;
            for (int p = 0; p < palSize; p++) {
                BlockState s = palette.valueFor(p);
                if (s != null && s.getLightEmission() != 0) {
                    emissiveFlags[p] = true;
                    anyEmissive = true;
                }
            }
            if (!anyEmissive) {
                return true;
            }
            zeroEmissive = emissiveFlags[0];
        } else {
            BlockState zeroState = palette.valueFor(0);
            zeroEmissive = zeroState != null && zeroState.getLightEmission() != 0;
        }

        if (bitStorage instanceof SimpleBitStorage) {
            int bits = bitStorage.getBits();
            long[] raw = bitStorage.getRaw();
            if (bits <= 0 || raw == null) {
                return false;
            }
            int valuesPerLong = 64 / bits;
            long valMask = (1L << bits) - 1L;
            int idx = 0;
            for (int wIdx = 0; wIdx < raw.length && idx < 4096; wIdx++) {
                long word = raw[wIdx];
                int limit = Math.min(valuesPerLong, 4096 - idx);
                if (word == 0L && !zeroEmissive) {
                    idx += limit;
                    continue;
                }
                for (int v = 0; v < limit; v++, idx++) {
                    int palIdx = (int) (word & valMask);
                    word >>>= bits;
                    chunking$maybeEnqueuePaletteVoxel(
                            palette, palSize, emissiveMask, emissiveFlags,
                            baseX, baseY, baseZ, idx, palIdx
                    );
                }
            }
            return true;
        }
        if (palSize <= 512 && bitStorage.getSize() == 4096) {
            int[] unpacked = new int[4096];
            bitStorage.unpack(unpacked);
            for (int idx = 0; idx < 4096; idx++) {
                chunking$maybeEnqueuePaletteVoxel(
                        palette, palSize, emissiveMask, emissiveFlags,
                        baseX, baseY, baseZ, idx, unpacked[idx]
                );
            }
            return true;
        }
        return false;
    }

    @Unique
    private void chunking$maybeEnqueuePaletteVoxel(
            Palette<BlockState> palette,
            int palSize,
            long emissiveMask,
            boolean[] emissiveFlags,
            int baseX,
            int baseY,
            int baseZ,
            int idx,
            int palIdx
    ) {
        if (palIdx < 0 || palIdx >= palSize) {
            return;
        }
        if (palSize <= 64) {
            if (((emissiveMask >>> palIdx) & 1L) == 0L) {
                return;
            }
        } else if (emissiveFlags != null && !emissiveFlags[palIdx]) {
            return;
        }
        BlockState s = palette.valueFor(palIdx);
        if (s != null) {
            int emission = s.getLightEmission();
            if (emission != 0) {
                chunking$enqueueVoxel(baseX, baseY, baseZ, idx, s, emission);
            }
        }
    }

    /**
     * @author Chunking
     * @reason Palette bitmask / emissive-flag array + 64-bit SimpleBitStorage word-skipping block light source scan without lambda or BlockPos allocations.
     */
    @Overwrite
    public void propagateLightSources(ChunkPos chunkPos) {
        this.setLightEnabled(chunkPos, true);
        LightChunk chunk = ((LightEngineAccess) this).chunking$isInLightUpdate()
                ? this.getChunk(chunkPos.x(), chunkPos.z())
                : this.chunkSource.getChunkForLighting(chunkPos.x(), chunkPos.z());
        if (chunk == null) {
            return;
        }
        ChunkAccess ca = (chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imp)
                ? imp.getWrapped()
                : (chunk instanceof ChunkAccess c ? c : null);
        if (ca == null || (ca instanceof LevelChunk lc && lc.getLevel().isDebug())) {
            chunk.findBlockLightSources((pos, state) -> this.enqueueIncrease(
                    pos.asLong(),
                    LightEngine.QueueEntry.increaseLightFromEmission(
                            state.getLightEmission(),
                            LightSpread.isEmptyShapeFast(state)
                    )
            ));
            return;
        }
        LevelChunkSection[] sections = ca.getSections();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
            LevelChunkSection sec = sections[secIdx];
            if (sec == null) {
                continue;
            }
            if (sec.hasOnlyAir()) {
                if (!CHUNKING$ANY_EMISSIVE_AIR) {
                    continue;
                }
                // Even if a mod registers a glowing AirBlock subclass, uniform vanilla-air sky sections still skip in O(1).
                if (CHUNKING$VANILLA_AIR_NON_EMISSIVE) {
                    PalettedDataAccess<BlockState> airData =
                            ((PalettedContainerAccess<BlockState>) (Object) sec.getStates()).chunking$dataAccess();
                    if (airData != null
                            && airData.chunking$palette() instanceof SingleValuePalette<BlockState> svp
                            && svp.getSize() > 0) {
                        BlockState only = svp.valueFor(0);
                        if (only == CHUNKING$AIR || only == CHUNKING$CAVE_AIR || only == CHUNKING$VOID_AIR) {
                            continue;
                        }
                    }
                }
            }
            int baseY = SectionPos.sectionToBlockCoord(ca.getSectionYFromSectionIndex(secIdx));
            PalettedDataAccess<BlockState> data =
                    ((PalettedContainerAccess<BlockState>) (Object) sec.getStates()).chunking$dataAccess();
            if (data != null && chunking$scanSectionData(data, baseX, baseY, baseZ)) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int wz = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState s = sec.getBlockState(lx, ly, lz);
                        int emission = s.getLightEmission();
                        if (emission != 0) {
                            this.enqueueIncrease(
                                    BlockPos.asLong(baseX + lx, wy, wz),
                                    LightEngine.QueueEntry.increaseLightFromEmission(
                                            emission,
                                            LightSpread.isEmptyShapeFast(s)
                                    )
                            );
                        }
                    }
                }
            }
        }
    }
}
