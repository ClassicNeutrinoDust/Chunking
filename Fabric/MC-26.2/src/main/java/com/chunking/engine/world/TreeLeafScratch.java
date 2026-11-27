package com.chunking.engine.world;

import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Thread-local primitive bucket scratch used by {@code TreeFeatureMixin} for zero-alloc leaf
 * distance propagation without {@code HashSet<BlockPos>} or per-leaf {@code BlockPos} churn.
 */
public final class TreeLeafScratch {
    private static final ThreadLocal<TreeLeafScratch> TL = ThreadLocal.withInitial(TreeLeafScratch::new);

    public final int[][] buckets = new int[7][];
    public final int[] sizes = new int[7];
    public byte[] queuedMask = new byte[1024];
    public final BlockPos.MutableBlockPos curPos = new BlockPos.MutableBlockPos();
    public final BlockPos.MutableBlockPos nbPos = new BlockPos.MutableBlockPos();
    public boolean inUse;

    public TreeLeafScratch() {
        for (int i = 0; i < 7; i++) {
            this.buckets[i] = new int[64];
        }
    }

    public static TreeLeafScratch acquire() {
        TreeLeafScratch tl = TL.get();
        TreeLeafScratch scratch = tl.inUse ? new TreeLeafScratch() : tl;
        scratch.inUse = true;
        return scratch;
    }

    public void push(int dist, int packed) {
        int sz = this.sizes[dist];
        int[] arr = this.buckets[dist];
        if (sz == arr.length) {
            arr = Arrays.copyOf(arr, arr.length << 1);
            this.buckets[dist] = arr;
        }
        arr[sz] = packed;
        this.sizes[dist] = sz + 1;
    }
}
