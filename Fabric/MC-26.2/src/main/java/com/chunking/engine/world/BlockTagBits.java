package com.chunking.engine.world;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.List;
import com.chunking.engine.access.BlockIdAccess;

/**
 * Compact {@code long[]} block-ID bitset for O(1) lock-free {@code TagKey<Block>} membership checks.
 */
public final class BlockTagBits {
    private static final long[] EMPTY = new long[0];
    private static volatile int tagEpoch = 1;

    private BlockTagBits() {}

    public static int tagEpoch() {
        return tagEpoch;
    }

    public static void invalidateTagCache() {
        tagEpoch++;
    }

    private static int blockId(Block block) {
        return (block instanceof BlockIdAccess acc)
                ? acc.chunking$blockId()
                : BuiltInRegistries.BLOCK.getId(block);
    }

    public static long[] build(List<? extends Holder<?>> contents) {
        if (contents == null || contents.isEmpty()) {
            return EMPTY;
        }
        int n = contents.size();
        int maxId = -1;
        for (int i = 0; i < n; i++) {
            Object v = contents.get(i).value();
            if (v instanceof Block b) {
                int id = blockId(b);
                if (id > maxId) {
                    maxId = id;
                }
            }
        }
        if (maxId < 0) {
            return EMPTY;
        }
        long[] bits = new long[(maxId >>> 6) + 1];
        for (int i = 0; i < n; i++) {
            Object v = contents.get(i).value();
            if (v instanceof Block b) {
                int id = blockId(b);
                if (id >= 0) {
                    bits[id >>> 6] |= (1L << id);
                }
            }
        }
        return bits;
    }

    public static boolean contains(long[] bits, Block block) {
        int id = blockId(block);
        int word = id >>> 6;
        return word >= 0 && word < bits.length && (bits[word] & (1L << id)) != 0L;
    }
}
