package com.chunking.engine.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable thread-safe 8x8 XZ spatial CSR index over a {@link StructureTemplate.Palette}'s block list.
 * Inverse-transforms the target chunk {@link BoundingBox} once into local template coordinates so
 * overlapping chunks iterate only intersecting cells while preserving exact original block order.
 */
public final class TemplateGrid {
    public static final int MIN_INDEX_BLOCKS = 64;
    private static final int CELL_SHIFT = 3;

    private static final class QueryScratch {
        final int[] bounds = new int[6];
        int[] matched = new int[256];

        int[] ensureMatched(int minCap) {
            if (this.matched.length < minCap) {
                this.matched = new int[Math.max(minCap, this.matched.length << 1)];
            }
            return this.matched;
        }
    }

    private static final ThreadLocal<QueryScratch> SCRATCH = ThreadLocal.withInitial(QueryScratch::new);

    public interface Access {
        List<StructureTemplate.StructureBlockInfo> chunking$queryBlocks(
                BlockPos origin,
                StructurePlaceSettings settings
        );
    }

    private final List<StructureTemplate.StructureBlockInfo> allBlocks;
    private final StructureTemplate.StructureBlockInfo[] blocksArr;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final int cellsX, cellsZ;
    private final int[] cellStarts;
    private final int[] indices;

    private TemplateGrid(
            List<StructureTemplate.StructureBlockInfo> allBlocks,
            StructureTemplate.StructureBlockInfo[] blocksArr,
            int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
            int cellsX, int cellsZ,
            int[] cellStarts,
            int[] indices
    ) {
        this.allBlocks = allBlocks;
        this.blocksArr = blocksArr;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.cellsX = cellsX;
        this.cellsZ = cellsZ;
        this.cellStarts = cellStarts;
        this.indices = indices;
    }

    public static TemplateGrid build(List<StructureTemplate.StructureBlockInfo> blocks) {
        int n = blocks.size();
        if (n < MIN_INDEX_BLOCKS) {
            return null;
        }
        StructureTemplate.StructureBlockInfo[] arr = blocks.toArray(new StructureTemplate.StructureBlockInfo[n]);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            BlockPos p = arr[i].pos();
            int x = p.getX(), y = p.getY(), z = p.getZ();
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        int cellsX = ((maxX - minX) >> CELL_SHIFT) + 1;
        int cellsZ = ((maxZ - minZ) >> CELL_SHIFT) + 1;
        long totalCellsLong = (long) cellsX * (long) cellsZ;
        if (totalCellsLong <= 0L || totalCellsLong > 65_536L) {
            return null;
        }
        int totalCells = (int) totalCellsLong;
        int[] counts = new int[totalCells];
        for (int i = 0; i < n; i++) {
            BlockPos p = arr[i].pos();
            int cx = (p.getX() - minX) >> CELL_SHIFT;
            int cz = (p.getZ() - minZ) >> CELL_SHIFT;
            counts[cx * cellsZ + cz]++;
        }

        int[] cellStarts = new int[totalCells + 1];
        int offset = 0;
        for (int c = 0; c < totalCells; c++) {
            cellStarts[c] = offset;
            offset += counts[c];
        }
        cellStarts[totalCells] = offset;

        int[] writeCursor = Arrays.copyOf(cellStarts, totalCells);
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = arr[i].pos();
            int cell = ((p.getX() - minX) >> CELL_SHIFT) * cellsZ + ((p.getZ() - minZ) >> CELL_SHIFT);
            indices[writeCursor[cell]++] = i;
        }

        return new TemplateGrid(
                blocks, arr, minX, maxX, minY, maxY, minZ, maxZ, cellsX, cellsZ, cellStarts, indices
        );
    }

    /**
     * Exact inverse of {@code StructureTemplate.calculateRelativePosition(settings, localPos).offset(origin)}.
     * Writes {@code [lMinX, lMinY, lMinZ, lMaxX, lMaxY, lMaxZ]} into {@code out6}.
     */
    public static void inverseTransformBounds(
            BoundingBox worldBox,
            BlockPos origin,
            Mirror mirror,
            Rotation rotation,
            BlockPos pivot,
            int[] out6
    ) {
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int px = pivot.getX();
        int pz = pivot.getZ();

        int rx0 = worldBox.minX() - ox;
        int rz0 = worldBox.minZ() - oz;
        int rx1 = worldBox.maxX() - ox;
        int rz1 = worldBox.maxZ() - oz;

        int mx0, mz0, mx1, mz1;
        switch (rotation) {
            case CLOCKWISE_180 -> {
                int twoPx = px + px;
                int twoPz = pz + pz;
                mx0 = twoPx - rx0;
                mz0 = twoPz - rz0;
                mx1 = twoPx - rx1;
                mz1 = twoPz - rz1;
            }
            case COUNTERCLOCKWISE_90 -> {
                mx0 = px + pz - rz0;
                mz0 = rx0 - px + pz;
                mx1 = px + pz - rz1;
                mz1 = rx1 - px + pz;
            }
            case CLOCKWISE_90 -> {
                mx0 = rz0 - pz + px;
                mz0 = px + pz - rx0;
                mx1 = rz1 - pz + px;
                mz1 = px + pz - rx1;
            }
            default -> {
                mx0 = rx0;
                mz0 = rz0;
                mx1 = rx1;
                mz1 = rz1;
            }
        }

        int lx0, lz0, lx1, lz1;
        switch (mirror) {
            case LEFT_RIGHT -> {
                lx0 = mx0;
                lz0 = -mz0;
                lx1 = mx1;
                lz1 = -mz1;
            }
            case FRONT_BACK -> {
                lx0 = -mx0;
                lz0 = mz0;
                lx1 = -mx1;
                lz1 = mz1;
            }
            default -> {
                lx0 = mx0;
                lz0 = mz0;
                lx1 = mx1;
                lz1 = mz1;
            }
        }

        out6[0] = Math.min(lx0, lx1);
        out6[1] = worldBox.minY() - oy;
        out6[2] = Math.min(lz0, lz1);
        out6[3] = Math.max(lx0, lx1);
        out6[4] = worldBox.maxY() - oy;
        out6[5] = Math.max(lz0, lz1);
    }

    public List<StructureTemplate.StructureBlockInfo> query(BlockPos origin, StructurePlaceSettings settings) {
        BoundingBox worldBox = settings.getBoundingBox();
        if (worldBox == null) {
            return this.allBlocks;
        }
        QueryScratch scratch = SCRATCH.get();
        int[] lb = scratch.bounds;
        inverseTransformBounds(
                worldBox, origin, settings.getMirror(), settings.getRotation(), settings.getRotationPivot(), lb
        );
        int lMinX = lb[0], lMinY = lb[1], lMinZ = lb[2];
        int lMaxX = lb[3], lMaxY = lb[4], lMaxZ = lb[5];

        if (lMaxX < this.minX || lMinX > this.maxX
                || lMaxY < this.minY || lMinY > this.maxY
                || lMaxZ < this.minZ || lMinZ > this.maxZ) {
            return List.of();
        }
        if (lMinX <= this.minX && lMaxX >= this.maxX
                && lMinY <= this.minY && lMaxY >= this.maxY
                && lMinZ <= this.minZ && lMaxZ >= this.maxZ) {
            return this.allBlocks;
        }

        int clampedMinX = Math.max(lMinX, this.minX);
        int clampedMaxX = Math.min(lMaxX, this.maxX);
        int clampedMinZ = Math.max(lMinZ, this.minZ);
        int clampedMaxZ = Math.min(lMaxZ, this.maxZ);

        int cx0 = (clampedMinX - this.minX) >> CELL_SHIFT;
        int cx1 = (clampedMaxX - this.minX) >> CELL_SHIFT;
        int cz0 = (clampedMinZ - this.minZ) >> CELL_SHIFT;
        int cz1 = (clampedMaxZ - this.minZ) >> CELL_SHIFT;

        int[] starts = this.cellStarts;
        int cZ = this.cellsZ;
        int nonEmptyCells = 0;
        int maxCandidates = 0;
        int singleCellId = -1;

        for (int cx = cx0; cx <= cx1; cx++) {
            int rowBase = cx * cZ;
            for (int cz = cz0; cz <= cz1; cz++) {
                int cell = rowBase + cz;
                int len = starts[cell + 1] - starts[cell];
                if (len > 0) {
                    nonEmptyCells++;
                    maxCandidates += len;
                    singleCellId = cell;
                }
            }
        }
        if (maxCandidates == 0) {
            return List.of();
        }

        StructureTemplate.StructureBlockInfo[] arr = this.blocksArr;
        int[] idxArr = this.indices;

        // A single overlapping cell is already in strictly ascending original block order.
        if (nonEmptyCells == 1) {
            int s = starts[singleCellId];
            int e = starts[singleCellId + 1];
            ArrayList<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(e - s);
            for (int i = s; i < e; i++) {
                StructureTemplate.StructureBlockInfo info = arr[idxArr[i]];
                BlockPos p = info.pos();
                int x = p.getX(), y = p.getY(), z = p.getZ();
                if (x >= lMinX && x <= lMaxX && y >= lMinY && y <= lMaxY && z >= lMinZ && z <= lMaxZ) {
                    out.add(info);
                }
            }
            return out;
        }

        int[] matched = scratch.ensureMatched(maxCandidates);
        int count = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            int rowBase = cx * cZ;
            for (int cz = cz0; cz <= cz1; cz++) {
                int cell = rowBase + cz;
                int s = starts[cell];
                int e = starts[cell + 1];
                for (int i = s; i < e; i++) {
                    int origIdx = idxArr[i];
                    BlockPos p = arr[origIdx].pos();
                    int x = p.getX(), y = p.getY(), z = p.getZ();
                    if (x >= lMinX && x <= lMaxX && y >= lMinY && y <= lMaxY && z >= lMinZ && z <= lMaxZ) {
                        matched[count++] = origIdx;
                    }
                }
            }
        }
        if (count == 0) {
            return List.of();
        }
        // Restore exact palette block order (full blocks -> NBT blocks -> attachments) and loot RNG order.
        Arrays.sort(matched, 0, count);
        ArrayList<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(arr[matched[i]]);
        }
        return out;
    }
}
