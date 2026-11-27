package com.chunking.engine.world;

import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.material.FluidState;

import java.util.Arrays;

/**
 * Allocation-free branchless block/fluid section counter matching vanilla
 * {@code LevelChunkSection$1BlockCounter} semantics while eliminating {@code unpack(int[])} and
 * {@code Int2IntOpenHashMap} churn on reloads.
 */
public final class SectionCounts {
    private static final ThreadLocal<byte[]> FLAGS_TL = ThreadLocal.withInitial(() -> new byte[256]);
    private static final ThreadLocal<int[]> COUNTS_TL = ThreadLocal.withInitial(() -> new int[256]);

    private SectionCounts() {}

    /**
     * Computes section counts directly into {@code outCounts[0..3]} =
     * {@code [nonEmptyBlockCount, tickingBlockCount, fluidCount, tickingFluidCount]}.
     * Returns {@code true} if handled, or {@code false} if caller should fall back to vanilla {@code count()}.
     */
    public static boolean tryCountDirect(Palette<BlockState> pal, BitStorage storage, int[] outCounts) {
        if (pal == null || storage == null || outCounts == null || outCounts.length < 4) {
            return false;
        }
        int palSize = pal.getSize();
        if (palSize <= 0) {
            return false;
        }
        if (palSize == 1 || storage instanceof ZeroBitStorage) {
            BlockState s = pal.valueFor(0);
            if (s == null || s.isAir()) {
                outCounts[0] = 0;
                outCounts[1] = 0;
                outCounts[2] = 0;
                outCounts[3] = 0;
            } else {
                int n = storage.getSize();
                outCounts[0] = n;
                outCounts[1] = s.isRandomlyTicking() ? n : 0;
                FluidState fs = s.getFluidState();
                if (!fs.isEmpty()) {
                    outCounts[2] = n;
                    outCounts[3] = fs.isRandomlyTicking() ? n : 0;
                } else {
                    outCounts[2] = 0;
                    outCounts[3] = 0;
                }
            }
            return true;
        }
        if (palSize > 256
                || !(pal instanceof LinearPalette || pal instanceof HashMapPalette)
                || !(storage instanceof SimpleBitStorage simple)) {
            return false;
        }
        int bits = simple.getBits();
        int entryCount = simple.getSize();
        if (bits <= 0 || bits > 8 || entryCount <= 0) {
            return false;
        }
        byte[] flags = FLAGS_TL.get();
        int orMask = 0;
        int andMask = 0xF;
        for (int id = 0; id < palSize; id++) {
            BlockState s = pal.valueFor(id);
            int f = 0;
            if (s != null && !s.isAir()) {
                f = 1;
                if (s.isRandomlyTicking()) {
                    f |= 2;
                }
                FluidState fs = s.getFluidState();
                if (!fs.isEmpty()) {
                    f |= 4;
                    if (fs.isRandomlyTicking()) {
                        f |= 8;
                    }
                }
            }
            flags[id] = (byte) f;
            orMask |= f;
            andMask &= f;
        }
        if (orMask == 0) {
            outCounts[0] = 0;
            outCounts[1] = 0;
            outCounts[2] = 0;
            outCounts[3] = 0;
            return true;
        }
        if (andMask == 1 && orMask == 1) {
            outCounts[0] = entryCount;
            outCounts[1] = 0;
            outCounts[2] = 0;
            outCounts[3] = 0;
            return true;
        }

        int[] counts = COUNTS_TL.get();
        Arrays.fill(counts, 0, palSize, 0);
        long[] raw = simple.getRaw();
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int fullWords = entryCount / valuesPerLong;
        int rem = entryCount - fullWords * valuesPerLong;

        for (int w = 0; w < fullWords; w++) {
            long word = raw[w];
            for (int k = 0; k < valuesPerLong; k++) {
                int id = (int) (word & mask);
                if (id < palSize) {
                    counts[id]++;
                }
                word >>>= bits;
            }
        }
        if (rem > 0) {
            long word = raw[fullWords];
            for (int k = 0; k < rem; k++) {
                int id = (int) (word & mask);
                if (id < palSize) {
                    counts[id]++;
                }
                word >>>= bits;
            }
        }

        int nonEmpty = 0;
        int tickingBlock = 0;
        int fluids = 0;
        int tickingFluid = 0;
        for (int id = 0; id < palSize; id++) {
            int c = counts[id];
            if (c == 0) {
                continue;
            }
            int f = flags[id] & 0xF;
            if ((f & 1) != 0) {
                nonEmpty += c;
                if ((f & 2) != 0) {
                    tickingBlock += c;
                }
                if ((f & 4) != 0) {
                    fluids += c;
                    if ((f & 8) != 0) {
                        tickingFluid += c;
                    }
                }
            }
        }
        outCounts[0] = nonEmpty;
        outCounts[1] = tickingBlock;
        outCounts[2] = fluids;
        outCounts[3] = tickingFluid;
        return true;
    }
}
