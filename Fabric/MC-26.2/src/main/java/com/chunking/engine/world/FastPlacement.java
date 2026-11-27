package com.chunking.engine.world;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/**
 * Direct section/palette column scanner for {@code CountOnEveryLayerPlacement},
 * avoiding per-Y {@code WorldGenRegion.getChunk} + {@code BlockPos} overhead.
 */
public final class FastPlacement {
    private FastPlacement() {}

    public static boolean isEmpty(BlockState state) {
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    public static int findOnGroundYFast(PlacementContext ctx, int x, int maxY, int z, int layer) {
        int minY = ctx.getMinY();
        if (maxY < minY + 1) {
            return Integer.MAX_VALUE;
        }
        ChunkAccess chunk = ctx.getLevel().getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        return findOnGroundYFromSections(chunk.getSections(), chunk.getMinY(), minY, x & 15, z & 15, maxY, layer);
    }

    public static int findOnGroundYFromSections(
            LevelChunkSection[] sections,
            int chunkMinY,
            int minY,
            int lx,
            int lz,
            int maxY,
            int layer
    ) {
        if (maxY < minY + 1 || sections.length == 0) {
            return Integer.MAX_VALUE;
        }
        int secCount = sections.length;
        int chunkMaxY = chunkMinY + (secCount << 4) - 1;

        boolean aboveEmpty;
        if (maxY > chunkMaxY || maxY < chunkMinY) {
            aboveEmpty = true;
        } else {
            LevelChunkSection topSec = sections[(maxY - chunkMinY) >> 4];
            aboveEmpty = topSec == null || topSec.hasOnlyAir() || isEmpty(topSec.getStates().get(lx, maxY & 15, lz));
        }

        int belowStartY = Math.min(maxY - 1, chunkMaxY);
        if (belowStartY < minY) {
            return Integer.MAX_VALUE;
        }
        int minSecIdx = Math.max(0, (minY - chunkMinY) >> 4);
        int startSecIdx = (belowStartY - chunkMinY) >> 4;
        int found = 0;

        for (int secIdx = startSecIdx; secIdx >= minSecIdx; secIdx--) {
            LevelChunkSection sec = sections[secIdx];
            if (sec == null || sec.hasOnlyAir()) {
                aboveEmpty = true;
                continue;
            }
            int secMinY = chunkMinY + (secIdx << 4);
            int topLy = (secIdx == startSecIdx) ? (belowStartY - secMinY) : 15;
            int botLy = (secIdx == minSecIdx) ? Math.max(0, minY - secMinY) : 0;
            PalettedContainer<BlockState> states = sec.getStates();
            for (int ly = topLy; ly >= botLy; ly--) {
                BlockState below = states.get(lx, ly, lz);
                boolean belowEmpty = isEmpty(below);
                if (!belowEmpty && aboveEmpty && !below.is(Blocks.BEDROCK)) {
                    if (found == layer) {
                        return secMinY + ly + 1;
                    }
                    found++;
                }
                aboveEmpty = belowEmpty;
            }
        }
        return Integer.MAX_VALUE;
    }
}
