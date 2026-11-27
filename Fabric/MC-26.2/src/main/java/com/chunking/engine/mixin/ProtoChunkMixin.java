package com.chunking.engine.mixin;

import com.chunking.engine.mixin.accessor.HeightmapAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Optimizes hot {@link ProtoChunk#setBlockState} calls via direct section array indexing,
 * lock-free {@link LevelChunkSection} writes, no-op ({@code prev == state}) light and heightmap skips,
 * solid-to-solid heightmap property equivalence skips, and a flat cached {@link Heightmap} + predicate
 * array keyed by {@link ChunkStatus}.
 */
@Mixin(value = ProtoChunk.class, priority = 500)
public abstract class ProtoChunkMixin extends ChunkAccess {
    protected ProtoChunkMixin(
            ChunkPos chunkPos,
            UpgradeData upgradeData,
            LevelHeightAccessor levelHeightAccessor,
            PalettedContainerFactory containerFactory,
            long inhabitedTime,
            LevelChunkSection[] sections,
            BlendingData blendingData
    ) {
        super(chunkPos, upgradeData, levelHeightAccessor, containerFactory, inhabitedTime, sections, blendingData);
    }

    @Shadow
    private volatile LevelLightEngine lightEngine;

    @Shadow
    private volatile ChunkStatus status;

    @Unique
    private static final BlockState CHUNKING$AIR = Blocks.AIR.defaultBlockState();

    @Unique
    private static final BlockState CHUNKING$VOID_AIR = Blocks.VOID_AIR.defaultBlockState();

    @Unique
    private static final Heightmap[] CHUNKING$EMPTY_HMS = new Heightmap[0];

    @Unique
    @SuppressWarnings("unchecked")
    private static final Predicate<BlockState>[] CHUNKING$EMPTY_PREDS = (Predicate<BlockState>[]) new Predicate[0];

    @Unique
    private record CachedHeightmaps(
            ChunkStatus status,
            Heightmap[] hms,
            Predicate<BlockState>[] preds,
            boolean allStandard,
            boolean hasNoLeaves
    ) {}

    // Non-static @Unique fields must have no inline initializers (JEP 513 / Mixin 0.8.7).
    @Unique
    private CachedHeightmaps chunking$hmCache;

    @Unique
    private LevelChunkSection[] chunking$sections;

    @Unique
    private int chunking$minSectionY;

    @Unique
    private static boolean chunking$sameStandardSolidHeightmapProps(BlockState prev, BlockState state, boolean hasNoLeaves) {
        if (!prev.isAir() && prev.blocksMotion() && !state.isAir() && state.blocksMotion()) {
            return !hasNoLeaves
                    || (!(prev.getBlock() instanceof LeavesBlock) && !(state.getBlock() instanceof LeavesBlock));
        }
        return false;
    }

    /**
     * @author Chunking
     * @reason Direct section indexing, lock-free section write, no-op &amp; heightmap predicate-equivalence skips, and flat cached Heightmap[] loop.
     */
    @Overwrite
    public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        LevelChunkSection[] secs = this.chunking$sections;
        int minSecY;
        if (secs == null) {
            secs = this.getSections();
            minSecY = this.getMinY() >> 4;
            this.chunking$sections = secs;
            this.chunking$minSectionY = minSecY;
        } else {
            minSecY = this.chunking$minSectionY;
        }
        int secIdx = (y >> 4) - minSecY;
        if (secIdx < 0 || secIdx >= secs.length) {
            return CHUNKING$VOID_AIR;
        }
        LevelChunkSection section = secs[secIdx];
        boolean wasEmpty = section.hasOnlyAir();
        if (wasEmpty && (state == CHUNKING$AIR || state.is(Blocks.AIR))) {
            return state;
        }
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        BlockState prev = section.setBlockState(lx, ly, lz, state, false);
        ChunkStatus curStatus = this.status;
        CachedHeightmaps cached = this.chunking$hmCache;
        if (prev == state) {
            if (cached != null && curStatus == cached.status()) {
                return prev;
            }
            chunking$ensureHeightmapsCached(curStatus, lx, y, lz, prev, state);
            return prev;
        }

        LevelLightEngine le = this.lightEngine;
        if (le != null && curStatus.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
            boolean nowEmpty = section.hasOnlyAir();
            if (nowEmpty != wasEmpty) {
                le.updateSectionStatus(pos, nowEmpty);
            }
            if (LightEngine.hasDifferentLightProperties(prev, state)) {
                this.skyLightSources.update(this, lx, y, lz);
                le.checkBlock(pos);
            }
        }

        if (cached != null && curStatus == cached.status()) {
            Heightmap[] hms = cached.hms();
            int len = hms.length;
            if (len != 0) {
                if (cached.allStandard() && chunking$sameStandardSolidHeightmapProps(prev, state, cached.hasNoLeaves())) {
                    for (int i = 0; i < len; i++) {
                        if (y + 1 > hms[i].getFirstAvailable(lx, lz)) {
                            hms[i].update(lx, y, lz, state);
                        }
                    }
                    return prev;
                }
                Predicate<BlockState>[] preds = cached.preds();
                for (int i = 0; i < len; i++) {
                    Predicate<BlockState> pred = preds[i];
                    if (pred == null || pred.test(prev) != pred.test(state)
                            || (pred.test(state) && y + 1 > hms[i].getFirstAvailable(lx, lz))) {
                        hms[i].update(lx, y, lz, state);
                    }
                }
            }
            return prev;
        }

        chunking$ensureHeightmapsCached(curStatus, lx, y, lz, prev, state);
        return prev;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void chunking$ensureHeightmapsCached(
            ChunkStatus curStatus,
            int lx,
            int y,
            int lz,
            BlockState prev,
            BlockState state
    ) {
        EnumSet<Heightmap.Types> after = curStatus.heightmapsAfter();
        EnumSet<Heightmap.Types> unprimed = null;
        for (Heightmap.Types type : after) {
            if (this.heightmaps.get(type) == null) {
                if (unprimed == null) {
                    unprimed = EnumSet.noneOf(Heightmap.Types.class);
                }
                unprimed.add(type);
            }
        }
        if (unprimed != null) {
            Heightmap.primeHeightmaps(this, unprimed);
        }

        int afterSize = after.size();
        if (afterSize == 0) {
            this.chunking$hmCache = new CachedHeightmaps(curStatus, CHUNKING$EMPTY_HMS, CHUNKING$EMPTY_PREDS, true, false);
            return;
        }

        Heightmap[] built = new Heightmap[afterSize];
        Predicate<BlockState>[] preds = (Predicate<BlockState>[]) new Predicate[afterSize];
        boolean allStandard = true;
        boolean hasNoLeaves = false;
        int count = 0;
        for (Heightmap.Types type : after) {
            Heightmap hm = this.heightmaps.get(type);
            if (hm != null) {
                built[count] = hm;
                Predicate<BlockState> pred = ((Object) hm instanceof HeightmapAccessor acc)
                        ? acc.chunking$getIsOpaque()
                        : null;
                preds[count] = pred;
                if (type == Heightmap.Types.MOTION_BLOCKING_NO_LEAVES) {
                    hasNoLeaves = true;
                } else if (type != Heightmap.Types.WORLD_SURFACE_WG
                        && type != Heightmap.Types.WORLD_SURFACE
                        && type != Heightmap.Types.OCEAN_FLOOR_WG
                        && type != Heightmap.Types.OCEAN_FLOOR
                        && type != Heightmap.Types.MOTION_BLOCKING) {
                    allStandard = false;
                }
                count++;
                if (prev != state && (pred == null || pred.test(prev) != pred.test(state))) {
                    hm.update(lx, y, lz, state);
                }
            }
        }
        if (count == afterSize) {
            this.chunking$hmCache = new CachedHeightmaps(curStatus, built, preds, allStandard, hasNoLeaves);
        }
    }
}
