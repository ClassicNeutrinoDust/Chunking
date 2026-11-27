package com.chunking.engine.mixin;

import com.chunking.engine.access.PalettedDataAccess;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

/**
 * Exposes underlying {@link BitStorage} and {@link Palette} on {@code PalettedContainer$Data}
 * and eliminates 4,096 per-voxel {@code Palette.valueFor}/{@code Palette.idFor} virtual lookups
 * inside {@code Data.copyFrom} on palette resizes via bulk palette remapping.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
public abstract class PalettedContainerDataMixin<T> implements PalettedDataAccess<T> {
    @Unique
    private static final ThreadLocal<int[]> CHUNKING$COPY_BUF_TL = ThreadLocal.withInitial(() -> new int[4096]);

    @Unique
    private static final ThreadLocal<int[]> CHUNKING$COPY_REMAP_TL = ThreadLocal.withInitial(() -> new int[256]);

    @Shadow
    @Final
    private BitStorage storage;

    @Shadow
    @Final
    private Palette<T> palette;

    @Override
    public BitStorage chunking$storage() {
        return this.storage;
    }

    @Override
    public Palette<T> chunking$palette() {
        return this.palette;
    }

    /**
     * @author Chunking
     * @reason Bulk palette-remap on PalettedContainer$Data.copyFrom cutting 4,096 valueFor+idFor virtual calls down to |palette| calls.
     */
    @Overwrite
    public void copyFrom(Palette<T> oldPalette, BitStorage oldStorage) {
        PaletteResize<T> noResize = PaletteResize.noResizeExpected();
        if (oldStorage instanceof ZeroBitStorage) {
            T val = oldPalette.valueFor(0);
            int id = this.palette.idFor(val, noResize);
            if (id != 0) {
                int n = this.storage.getSize();
                for (int i = 0; i < n; i++) {
                    this.storage.set(i, id);
                }
            }
            return;
        }
        int oldPalSize = oldPalette.getSize();
        int n = oldStorage.getSize();
        if (oldPalSize <= 256 && n <= 4096
                && (oldPalette instanceof net.minecraft.world.level.chunk.LinearPalette
                || oldPalette instanceof net.minecraft.world.level.chunk.HashMapPalette)) {
            int[] buf = CHUNKING$COPY_BUF_TL.get();
            oldStorage.unpack(buf);
            int[] remap = CHUNKING$COPY_REMAP_TL.get();
            Arrays.fill(remap, 0, oldPalSize, -1);
            boolean identity = true;
            for (int i = 0; i < n; i++) {
                int oldId = buf[i];
                int newId = remap[oldId];
                if (newId < 0) {
                    newId = this.palette.idFor(oldPalette.valueFor(oldId), noResize);
                    remap[oldId] = newId;
                }
                if (newId != oldId) {
                    identity = false;
                }
                buf[i] = newId;
            }
            BitStorage dst = this.storage;
            if (dst instanceof ZeroBitStorage) {
                return;
            }
            if (identity && oldStorage instanceof SimpleBitStorage srcSimple && dst instanceof SimpleBitStorage dstSimple) {
                int srcBits = srcSimple.getBits();
                int dstBits = dstSimple.getBits();
                if (srcBits == dstBits) {
                    long[] sRaw = srcSimple.getRaw();
                    long[] dRaw = dstSimple.getRaw();
                    System.arraycopy(sRaw, 0, dRaw, 0, Math.min(sRaw.length, dRaw.length));
                    return;
                }
            }
            if (dst instanceof SimpleBitStorage dstSimple) {
                int bits = dstSimple.getBits();
                if (bits > 0) {
                    int valuesPerLong = 64 / bits;
                    long[] dRaw = dstSimple.getRaw();
                    int wordIdx = 0;
                    int i = 0;
                    int limit = n - valuesPerLong;
                    while (i <= limit) {
                        long word = 0L;
                        for (int k = valuesPerLong - 1; k >= 0; k--) {
                            word = (word << bits) | (buf[i + k] & ((1L << bits) - 1L));
                        }
                        dRaw[wordIdx++] = word;
                        i += valuesPerLong;
                    }
                    int rem = n - i;
                    if (rem > 0) {
                        long word = 0L;
                        for (int k = rem - 1; k >= 0; k--) {
                            word = (word << bits) | (buf[i + k] & ((1L << bits) - 1L));
                        }
                        dRaw[wordIdx] = word;
                    }
                    return;
                }
            }
            for (int i = 0; i < n; i++) {
                dst.set(i, buf[i]);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            T val = oldPalette.valueFor(oldStorage.get(i));
            this.storage.set(i, this.palette.idFor(val, noResize));
        }
    }
}
