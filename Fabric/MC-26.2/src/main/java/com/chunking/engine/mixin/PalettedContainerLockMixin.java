package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.StrategyAccessor;
import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Lock-elision ({@code acquire}/{@code release}/{@code ThreadingDetector}) and zero-alloc
 * {@code pack}/{@code count} optimizations on {@link PalettedContainer}. Disabled automatically by
 * {@link MixinPlugin} when Lithium is installed (Lithium provides its own {@code acquire}/
 * {@code release}, {@code pack}, and {@code count} mixins).
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerLockMixin<T> {
    @Shadow
    @Final
    private ThreadingDetector threadingDetector;

    @Unique
    private static final boolean CHUNKING$ENABLE_THREADING_DETECTOR = Boolean.getBoolean("chunking.threading_detector");

    @Unique
    private static final ThreadingDetector CHUNKING$SHARED_NOOP_DETECTOR = new ThreadingDetector("PalettedContainer");

    @Unique
    private static final ThreadLocal<int[]> CHUNKING$PACK_SCRATCH_TL = ThreadLocal.withInitial(() -> new int[4096]);

    @Unique
    private static final ThreadLocal<int[]> CHUNKING$REMAP_SCRATCH_TL = ThreadLocal.withInitial(() -> new int[256]);

    @Redirect(
            method = {
                    "<init>(Lnet/minecraft/world/level/chunk/Strategy;Lnet/minecraft/world/level/chunk/Configuration;Lnet/minecraft/util/BitStorage;Lnet/minecraft/world/level/chunk/Palette;)V",
                    "<init>(Lnet/minecraft/world/level/chunk/PalettedContainer;)V",
                    "<init>(Ljava/lang/Object;Lnet/minecraft/world/level/chunk/Strategy;)V"
            },
            at = @At(value = "NEW", target = "(Ljava/lang/String;)Lnet/minecraft/util/ThreadingDetector;")
    )
    private ThreadingDetector chunking$reuseNoopThreadingDetector(String name) {
        return CHUNKING$ENABLE_THREADING_DETECTOR ? new ThreadingDetector(name) : CHUNKING$SHARED_NOOP_DETECTOR;
    }

    /**
     * @author Chunking
     * @reason Bypass ThreadingDetector Semaphore/ReentrantLock barriers on parallel worker chunk writes.
     */
    @Overwrite
    public void acquire() {
        if (CHUNKING$ENABLE_THREADING_DETECTOR) {
            this.threadingDetector.checkAndLock();
        }
    }

    /**
     * @author Chunking
     * @reason Bypass ThreadingDetector Semaphore/ReentrantLock barriers on parallel worker chunk writes.
     */
    @Overwrite
    public void release() {
        if (CHUNKING$ENABLE_THREADING_DETECTOR) {
            this.threadingDetector.checkAndUnlock();
        }
    }

    @WrapMethod(method = "pack(Lnet/minecraft/world/level/chunk/Strategy;)Lnet/minecraft/world/level/chunk/PalettedContainerRO$PackedData;")
    private PalettedContainerRO.PackedData<T> chunking$fastPackZeroAlloc(
            Strategy<T> strategy,
            Operation<PalettedContainerRO.PackedData<T>> original
    ) {
        if (!ModConfig.SIMD_NOISE) {
            return original.call(strategy);
        }
        this.acquire();
        try {
            @SuppressWarnings("unchecked")
            PalettedDataAccess<T> d = ((PalettedContainerAccess<T>) this).chunking$dataAccess();
            if (d == null) {
                return original.call(strategy);
            }
            BitStorage storage = d.chunking$storage();
            Palette<T> oldPal = d.chunking$palette();
            int oldSize = oldPal.getSize();
            if (storage instanceof ZeroBitStorage || oldSize == 1) {
                return new PalettedContainerRO.PackedData<>(List.of(oldPal.valueFor(0)), Optional.empty(), 0);
            }
            if ((oldPal instanceof LinearPalette || oldPal instanceof HashMapPalette) && oldSize <= 256) {
                int entryCount = strategy.entryCount();
                int[] buf = CHUNKING$PACK_SCRATCH_TL.get();
                if (buf.length < entryCount) {
                    buf = new int[entryCount];
                    CHUNKING$PACK_SCRATCH_TL.set(buf);
                }
                storage.unpack(buf);
                int[] remap = CHUNKING$REMAP_SCRATCH_TL.get();
                Arrays.fill(remap, 0, oldSize, 0);
                int usedCount = 0;
                for (int i = 0; i < entryCount; i++) {
                    int oldId = buf[i];
                    if (remap[oldId] == 0) {
                        remap[oldId] = 1;
                        if (++usedCount == oldSize) {
                            break;
                        }
                    }
                }
                if (usedCount == 1) {
                    int singleId = 0;
                    for (int id = 0; id < oldSize; id++) {
                        if (remap[id] != 0) {
                            singleId = id;
                            break;
                        }
                    }
                    return new PalettedContainerRO.PackedData<>(List.of(oldPal.valueFor(singleId)), Optional.empty(), 0);
                }
                Configuration cfg = ((StrategyAccessor) (Object) strategy)
                        .chunking$getConfigForPaletteSize(usedCount);
                int bits = cfg.bitsInStorage();
                if (usedCount == oldSize && bits == storage.getBits() && storage instanceof SimpleBitStorage) {
                    ArrayList<T> compact = new ArrayList<>(oldSize);
                    for (int id = 0; id < oldSize; id++) {
                        compact.add(oldPal.valueFor(id));
                    }
                    return new PalettedContainerRO.PackedData<>(
                            compact,
                            Optional.of(Arrays.stream(storage.getRaw().clone())),
                            bits
                    );
                }
                ArrayList<T> compact = new ArrayList<>(usedCount);
                int nextId = 0;
                for (int oldId = 0; oldId < oldSize; oldId++) {
                    if (remap[oldId] != 0) {
                        remap[oldId] = nextId++;
                        compact.add(oldPal.valueFor(oldId));
                    }
                }
                for (int i = 0; i < entryCount; i++) {
                    buf[i] = remap[buf[i]];
                }
                Optional<LongStream> opt;
                if (bits == 0) {
                    opt = Optional.empty();
                } else {
                    SimpleBitStorage repacked = new SimpleBitStorage(bits, entryCount, buf);
                    opt = Optional.of(Arrays.stream(repacked.getRaw()));
                }
                return new PalettedContainerRO.PackedData<>(compact, opt, bits);
            }
            return original.call(strategy);
        } finally {
            this.release();
        }
    }

    /**
     * @author Chunking
     * @reason Zero-alloc array-histogram count without MixinExtras @WrapMethod wrapper overhead.
     */
    @Overwrite
    public void count(PalettedContainer.CountConsumer<T> consumer) {
        @SuppressWarnings("unchecked")
        PalettedDataAccess<T> d = ((PalettedContainerAccess<T>) this).chunking$dataAccess();
        if (d == null) {
            throw new IllegalStateException("PalettedContainer data unavailable");
        }
        Palette<T> pal = d.chunking$palette();
        BitStorage storage = d.chunking$storage();
        int palSize = pal.getSize();
        if (palSize == 1 || storage instanceof ZeroBitStorage) {
            consumer.accept(pal.valueFor(0), storage.getSize());
            return;
        }
        if (ModConfig.SIMD_NOISE && (pal instanceof LinearPalette || pal instanceof HashMapPalette) && palSize <= 256) {
            int entryCount = storage.getSize();
            int[] buf = CHUNKING$PACK_SCRATCH_TL.get();
            if (buf.length < entryCount) {
                buf = new int[entryCount];
                CHUNKING$PACK_SCRATCH_TL.set(buf);
            }
            storage.unpack(buf);
            int[] counts = CHUNKING$REMAP_SCRATCH_TL.get();
            Arrays.fill(counts, 0, palSize, 0);
            for (int i = 0; i < entryCount; i++) {
                counts[buf[i]]++;
            }
            for (int id = 0; id < palSize; id++) {
                int c = counts[id];
                if (c != 0) {
                    consumer.accept(pal.valueFor(id), c);
                }
            }
            return;
        }
        Int2IntOpenHashMap countsMap = new Int2IntOpenHashMap();
        storage.getAll(id -> countsMap.addTo(id, 1));
        countsMap.int2IntEntrySet().forEach(entry -> consumer.accept(pal.valueFor(entry.getIntKey()), entry.getIntValue()));
    }
}
