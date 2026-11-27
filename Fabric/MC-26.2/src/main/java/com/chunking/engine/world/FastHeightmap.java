package com.chunking.engine.world;

import com.chunking.engine.mixin.accessor.HeightmapAccessor;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;

/**
 * Section-major top-down implementation of {@link Heightmap#primeHeightmaps(ChunkAccess, Set)}
 * that scans {@code secIdx -> ly -> z -> x} with a 256-bit remaining-columns bitset, pre-evaluates
 * heightmap predicates once per palette entry (with O(1) uniform-section and non-matching-section
 * skips), and reads 4-bit palette IDs directly from {@link BitStorage#getRaw()} when available.
 */
public final class FastHeightmap {
    private static final class Scratch {
        final int[] colMask = new int[256];
        final long[] remCols = new long[4];
        final int[] hmRem = new int[32];
        final int[] palMasks = new int[256];
    }

    private static final ThreadLocal<Scratch> SCRATCH_TL = ThreadLocal.withInitial(Scratch::new);

    private FastHeightmap() {}

    @SuppressWarnings("unchecked")
    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        if (types == null || types.isEmpty()) {
            return;
        }
        int count = types.size();
        Heightmap[] allMaps = new Heightmap[count];
        Predicate<BlockState>[] allPreds = (Predicate<BlockState>[]) new Predicate[count];
        int idx = 0;
        for (Heightmap.Types type : types) {
            Heightmap hm = chunk.getOrCreateHeightmapUnprimed(type);
            allMaps[idx] = hm;
            allPreds[idx] = ((Object) hm instanceof HeightmapAccessor acc)
                    ? acc.chunking$getIsOpaque()
                    : ReflectFallback.getIsOpaque(hm);
            idx++;
        }

        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null || sections.length == 0) {
            return;
        }
        int topSecIdx = -1;
        for (int s = sections.length - 1; s >= 0; s--) {
            LevelChunkSection sec = sections[s];
            if (sec != null && !sec.hasOnlyAir()) {
                topSecIdx = s;
                break;
            }
        }
        if (topSecIdx < 0) {
            return;
        }
        int minY = chunk.getMinY();
        int maxY = minY + ((topSecIdx + 1) << 4);
        BlockState air = Blocks.AIR.defaultBlockState();

        if (count > 32) {
            primeHeightmapsFallbackMany(chunk, sections, topSecIdx, maxY, minY, allMaps, allPreds, count, air);
            return;
        }

        Scratch scratch = SCRATCH_TL.get();
        int allMask = (count == 32) ? -1 : ((1 << count) - 1);
        int[] colMask = scratch.colMask;
        Arrays.fill(colMask, allMask);
        long[] remCols = scratch.remCols;
        remCols[0] = -1L;
        remCols[1] = -1L;
        remCols[2] = -1L;
        remCols[3] = -1L;
        int[] hmRem = scratch.hmRem;
        for (int k = 0; k < count; k++) {
            hmRem[k] = 256;
        }
        int unionMask = allMask;
        int remainingCols = 256;
        int[] palMasks = scratch.palMasks;

        for (int secIdx = topSecIdx; secIdx >= 0 && remainingCols > 0 && unionMask != 0; secIdx--) {
            LevelChunkSection sec = sections[secIdx];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            int secBaseY = chunk.getSectionYFromSectionIndex(secIdx) << 4;
            int topLy = Math.min(15, (maxY - 1) - secBaseY);
            int botLy = Math.max(0, minY - secBaseY);
            if (topLy < botLy) {
                continue;
            }

            PalettedContainer<BlockState> states = sec.getStates();
            if ((Object) states instanceof PalettedContainerAccess<?> rawAcc) {
                PalettedContainerAccess<BlockState> palAcc = (PalettedContainerAccess<BlockState>) rawAcc;
                PalettedDataAccess<BlockState> d = palAcc.chunking$dataAccess();
                if (d != null) {
                    Palette<BlockState> pal = d.chunking$palette();
                    BitStorage storage = d.chunking$storage();
                    int palSize = (pal != null) ? pal.getSize() : 0;

                    if (storage instanceof ZeroBitStorage || palSize == 1) {
                        BlockState single = pal != null ? pal.valueFor(0) : null;
                        int stateMask = evalStateMask(single, air, allPreds, unionMask);
                        if (stateMask == 0) {
                            continue;
                        }
                        int hitY = secBaseY + topLy + 1;
                        for (int w = 0; w < 4 && remainingCols > 0; w++) {
                            long word = remCols[w];
                            while (word != 0L) {
                                int bit = Long.numberOfTrailingZeros(word);
                                word &= word - 1L;
                                int col = (w << 6) | bit;
                                int hitMask = stateMask & colMask[col];
                                if (hitMask != 0) {
                                    int x = col & 15;
                                    int z = col >>> 4;
                                    unionMask = recordHits(allMaps, hmRem, unionMask, hitMask, x, z, hitY);
                                    int newMask = colMask[col] & ~hitMask;
                                    colMask[col] = newMask;
                                    if (newMask == 0) {
                                        remCols[w] &= ~(1L << bit);
                                        remainingCols--;
                                    }
                                }
                            }
                        }
                        continue;
                    }

                    if (palSize > 0 && palSize <= 256
                            && (pal instanceof LinearPalette || pal instanceof HashMapPalette)
                            && storage instanceof SimpleBitStorage simple) {
                        int sectionOrMask = 0;
                        for (int id = 0; id < palSize; id++) {
                            int m = evalStateMask(pal.valueFor(id), air, allPreds, unionMask);
                            palMasks[id] = m;
                            sectionOrMask |= m;
                        }
                        if (sectionOrMask == 0) {
                            continue;
                        }

                        Strategy<BlockState> strat = palAcc.chunking$strategy();
                        long[] raw = simple.getRaw();
                        boolean fast4 = simple.getBits() == 4
                                && raw != null && raw.length == 256
                                && strat != null
                                && strat.getIndex(1, 0, 0) == 1
                                && strat.getIndex(0, 0, 1) == 16
                                && strat.getIndex(0, 1, 0) == 256;

                        if (fast4) {
                            for (int ly = topLy; ly >= botLy && remainingCols > 0 && (sectionOrMask & unionMask) != 0; ly--) {
                                int hitY = secBaseY + ly + 1;
                                int yBase = ly << 4;
                                for (int w = 0; w < 4; w++) {
                                    long word = remCols[w];
                                    if (word == 0L) {
                                        continue;
                                    }
                                    while (word != 0L) {
                                        int bit = Long.numberOfTrailingZeros(word);
                                        word &= word - 1L;
                                        int col = (w << 6) | bit;
                                        int x = col & 15;
                                        int z = col >>> 4;
                                        int palId = (int) ((raw[yBase | z] >>> (x << 2)) & 0xFL);
                                        if (palId >= palSize) {
                                            continue;
                                        }
                                        int hitMask = palMasks[palId] & colMask[col];
                                        if (hitMask != 0) {
                                            unionMask = recordHits(allMaps, hmRem, unionMask, hitMask, x, z, hitY);
                                            int newMask = colMask[col] & ~hitMask;
                                            colMask[col] = newMask;
                                            if (newMask == 0) {
                                                remCols[w] &= ~(1L << bit);
                                                remainingCols--;
                                            }
                                        }
                                    }
                                }
                            }
                            continue;
                        }

                        if (strat != null) {
                            for (int ly = topLy; ly >= botLy && remainingCols > 0 && (sectionOrMask & unionMask) != 0; ly--) {
                                int hitY = secBaseY + ly + 1;
                                for (int w = 0; w < 4; w++) {
                                    long word = remCols[w];
                                    if (word == 0L) {
                                        continue;
                                    }
                                    while (word != 0L) {
                                        int bit = Long.numberOfTrailingZeros(word);
                                        word &= word - 1L;
                                        int col = (w << 6) | bit;
                                        int x = col & 15;
                                        int z = col >>> 4;
                                        int palId = simple.get(strat.getIndex(x, ly, z));
                                        if (palId >= palSize) {
                                            continue;
                                        }
                                        int hitMask = palMasks[palId] & colMask[col];
                                        if (hitMask != 0) {
                                            unionMask = recordHits(allMaps, hmRem, unionMask, hitMask, x, z, hitY);
                                            int newMask = colMask[col] & ~hitMask;
                                            colMask[col] = newMask;
                                            if (newMask == 0) {
                                                remCols[w] &= ~(1L << bit);
                                                remainingCols--;
                                            }
                                        }
                                    }
                                }
                            }
                            continue;
                        }
                    }
                }
            }

            // Section-major top-down fallback when palette internals are unavailable (e.g. GlobalPalette or unit tests)
            for (int ly = topLy; ly >= botLy && remainingCols > 0 && unionMask != 0; ly--) {
                int hitY = secBaseY + ly + 1;
                for (int w = 0; w < 4; w++) {
                    long word = remCols[w];
                    if (word == 0L) {
                        continue;
                    }
                    while (word != 0L) {
                        int bit = Long.numberOfTrailingZeros(word);
                        word &= word - 1L;
                        int col = (w << 6) | bit;
                        int x = col & 15;
                        int z = col >>> 4;
                        BlockState state = sec.getBlockState(x, ly, z);
                        if (state == air || state.is(Blocks.AIR)) {
                            continue;
                        }
                        int need = colMask[col];
                        int hitMask = 0;
                        int m = need;
                        while (m != 0) {
                            int k = Integer.numberOfTrailingZeros(m);
                            m &= m - 1;
                            if (allPreds[k].test(state)) {
                                hitMask |= (1 << k);
                            }
                        }
                        if (hitMask != 0) {
                            unionMask = recordHits(allMaps, hmRem, unionMask, hitMask, x, z, hitY);
                            int newMask = need & ~hitMask;
                            colMask[col] = newMask;
                            if (newMask == 0) {
                                remCols[w] &= ~(1L << bit);
                                remainingCols--;
                            }
                        }
                    }
                }
            }
        }
    }

    private static int evalStateMask(BlockState state, BlockState air, Predicate<BlockState>[] preds, int unionMask) {
        if (state == null || state == air || state.is(Blocks.AIR)) {
            return 0;
        }
        int mask = 0;
        int m = unionMask;
        while (m != 0) {
            int k = Integer.numberOfTrailingZeros(m);
            m &= m - 1;
            if (preds[k].test(state)) {
                mask |= (1 << k);
            }
        }
        return mask;
    }

    private static int recordHits(
            Heightmap[] allMaps,
            int[] hmRem,
            int unionMask,
            int hitMask,
            int x,
            int z,
            int hitY
    ) {
        int m = hitMask;
        while (m != 0) {
            int k = Integer.numberOfTrailingZeros(m);
            m &= m - 1;
            Heightmap hm = allMaps[k];
            if ((Object) hm instanceof HeightmapAccessor acc) {
                acc.chunking$invokeSetHeight(x, z, hitY);
            } else {
                ReflectFallback.setHeight(hm, x, z, hitY);
            }
            if (--hmRem[k] == 0) {
                unionMask &= ~(1 << k);
            }
        }
        return unionMask;
    }

    @SuppressWarnings("unchecked")
    private static void primeHeightmapsFallbackMany(
            ChunkAccess chunk,
            LevelChunkSection[] sections,
            int topSecIdx,
            int maxY,
            int minY,
            Heightmap[] allMaps,
            Predicate<BlockState>[] allPreds,
            int count,
            BlockState air
    ) {
        Heightmap[] activeMaps = new Heightmap[count];
        Predicate<BlockState>[] activePreds = (Predicate<BlockState>[]) new Predicate[count];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                System.arraycopy(allMaps, 0, activeMaps, 0, count);
                System.arraycopy(allPreds, 0, activePreds, 0, count);
                int rem = count;
                for (int secIdx = topSecIdx; secIdx >= 0 && rem > 0; secIdx--) {
                    LevelChunkSection sec = sections[secIdx];
                    if (sec == null || sec.hasOnlyAir()) {
                        continue;
                    }
                    int secBaseY = chunk.getSectionYFromSectionIndex(secIdx) << 4;
                    int topLy = Math.min(15, (maxY - 1) - secBaseY);
                    int botLy = Math.max(0, minY - secBaseY);
                    for (int ly = topLy; ly >= botLy; ly--) {
                        BlockState state = sec.getBlockState(x, ly, z);
                        if (state == air || state.is(Blocks.AIR)) {
                            continue;
                        }
                        int hitY = secBaseY + ly + 1;
                        int k = 0;
                        while (k < rem) {
                            if (activePreds[k].test(state)) {
                                Heightmap hm = activeMaps[k];
                                if ((Object) hm instanceof HeightmapAccessor acc) {
                                    acc.chunking$invokeSetHeight(x, z, hitY);
                                } else {
                                    ReflectFallback.setHeight(hm, x, z, hitY);
                                }
                                activeMaps[k] = activeMaps[--rem];
                                activePreds[k] = activePreds[rem];
                            } else {
                                k++;
                            }
                        }
                        if (rem == 0) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private static final class ReflectFallback {
        private static final Field IS_OPAQUE_FIELD;
        private static final Method SET_HEIGHT_METHOD;

        static {
            try {
                IS_OPAQUE_FIELD = Heightmap.class.getDeclaredField("isOpaque");
                IS_OPAQUE_FIELD.setAccessible(true);
                SET_HEIGHT_METHOD = Heightmap.class.getDeclaredMethod("setHeight", int.class, int.class, int.class);
                SET_HEIGHT_METHOD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize Heightmap reflection fallback", e);
            }
        }

        @SuppressWarnings("unchecked")
        static Predicate<BlockState> getIsOpaque(Heightmap hm) {
            try {
                return (Predicate<BlockState>) IS_OPAQUE_FIELD.get(hm);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static void setHeight(Heightmap hm, int x, int z, int y) {
            try {
                SET_HEIGHT_METHOD.invoke(hm, x, z, y);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
