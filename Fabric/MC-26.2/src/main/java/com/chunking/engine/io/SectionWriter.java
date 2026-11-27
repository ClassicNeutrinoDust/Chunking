package com.chunking.engine.io;

import com.chunking.engine.mixin.accessor.StrategyAccessor;
import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct zero-DFU-Codec NBT encoder for {@code SerializableChunkData.write()} section
 * {@code "block_states"} and {@code "biomes"} {@link PalettedContainer}s, including
 * {@link GlobalPalette} sections with {@code >256} unique entries.
 */
public final class SectionWriter {
    private static final class Scratch {
        int[] packBuf = new int[4096];
        final int[] remapBuf = new int[256];
        final Tag[] entryTags = new Tag[256];
        int[] usedOldIds = new int[512];
        final Int2IntOpenHashMap globalRemap = createGlobalRemap();

        private static Int2IntOpenHashMap createGlobalRemap() {
            Int2IntOpenHashMap m = new Int2IntOpenHashMap(512);
            m.defaultReturnValue(-1);
            return m;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH_TL = ThreadLocal.withInitial(Scratch::new);

    private static final Object STATE_CACHE_GROW_LOCK = new Object();
    private static final java.lang.invoke.VarHandle STATE_CACHE_ELEM =
            java.lang.invoke.MethodHandles.arrayElementVarHandle(CompoundTag[].class);
    private static volatile CompoundTag[] stateTagCache = new CompoundTag[initialStateCacheSize()];
    private static final int MAX_BIOME_TAG_CACHE = 8_192;
    private static final ConcurrentHashMap<Holder<Biome>, StringTag> BIOME_TAG_CACHE = new ConcurrentHashMap<>(128);

    private SectionWriter() {}

    private static int registryBlockStateCount() {
        try {
            return Block.BLOCK_STATE_REGISTRY.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int initialStateCacheSize() {
        return Math.max(32_768, registryBlockStateCount() + 1024);
    }

    public static CompoundTag getBlockStateTag(BlockState state) {
        if (state == null) {
            return null;
        }
        int id = Block.getId(state);
        if (id < 0) {
            return null;
        }
        CompoundTag[] cache = stateTagCache;
        if (id < cache.length) {
            CompoundTag hit = (CompoundTag) STATE_CACHE_ELEM.getAcquire(cache, id);
            if (hit != null) {
                return hit;
            }
        } else {
            synchronized (STATE_CACHE_GROW_LOCK) {
                cache = stateTagCache;
                if (id >= cache.length) {
                    int target = Math.max(Math.max(cache.length << 1, id + 1024), registryBlockStateCount() + 1024);
                    cache = Arrays.copyOf(cache, target);
                    stateTagCache = cache;
                }
            }
        }
        Tag encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result().orElse(null);
        if (encoded instanceof CompoundTag ct) {
            synchronized (STATE_CACHE_GROW_LOCK) {
                cache = stateTagCache;
                if (id >= cache.length) {
                    int target = Math.max(Math.max(cache.length << 1, id + 1024), registryBlockStateCount() + 1024);
                    cache = Arrays.copyOf(cache, target);
                    stateTagCache = cache;
                }
                STATE_CACHE_ELEM.setRelease(cache, id, ct);
            }
            return ct;
        }
        return null;
    }

    public static StringTag getBiomeTag(Holder<Biome> biome) {
        if (biome == null) {
            return null;
        }
        StringTag cached = BIOME_TAG_CACHE.get(biome);
        if (cached != null) {
            return cached;
        }
        var keyOpt = biome.unwrapKey();
        if (keyOpt.isPresent()) {
            StringTag tag = StringTag.valueOf(keyOpt.get().identifier().toString());
            if (BIOME_TAG_CACHE.size() >= MAX_BIOME_TAG_CACHE) {
                BIOME_TAG_CACHE.clear();
            }
            BIOME_TAG_CACHE.putIfAbsent(biome, tag);
            return tag;
        }
        return null;
    }

    public static void clearBiomeCache() {
        BIOME_TAG_CACHE.clear();
    }

    public static CompoundTag encodeBlockStates(PalettedContainer<BlockState> container) {
        return encodeContainer(container, true);
    }

    public static CompoundTag encodeBiomes(PalettedContainer<Holder<Biome>> container) {
        return encodeContainer(container, false);
    }

    @SuppressWarnings("unchecked")
    private static <T> CompoundTag encodeContainer(PalettedContainer<T> container, boolean isBlockState) {
        if ((Object) container instanceof PalettedContainerAccess<?> rawAcc) {
            PalettedContainerAccess<T> acc = (PalettedContainerAccess<T>) rawAcc;
            Strategy<T> strategy = acc.chunking$strategy();
            if (strategy == null) {
                return null;
            }
            container.acquire();
            try {
                PalettedDataAccess<T> d = acc.chunking$dataAccess();
                if (d == null) {
                    return null;
                }
                return encodeContainerCore(strategy, d.chunking$storage(), d.chunking$palette(), isBlockState, true);
            } finally {
                container.release();
            }
        }
        Strategy<T> strategy = ReflectFallback.getStrategy(container);
        if (strategy == null) {
            return null;
        }
        container.acquire();
        try {
            Object dataObj = ReflectFallback.getData(container);
            if (dataObj == null) {
                return null;
            }
            BitStorage storage = ReflectFallback.getStorage(dataObj);
            Palette<T> oldPal = ReflectFallback.getPalette(dataObj);
            return encodeContainerCore(strategy, storage, oldPal, isBlockState, false);
        } finally {
            container.release();
        }
    }

    private static <T> CompoundTag encodeContainerCore(
            Strategy<T> strategy,
            BitStorage storage,
            Palette<T> oldPal,
            boolean isBlockState,
            boolean useStrategyAccessor
    ) {
        if (storage == null || oldPal == null) {
            return null;
        }
        int oldSize = oldPal.getSize();
        if (oldSize <= 0) {
            return null;
        }

        if (storage instanceof ZeroBitStorage || oldSize == 1) {
            Tag singleTag = encodeEntry(oldPal.valueFor(0), isBlockState);
            if (singleTag == null) {
                return null;
            }
            ListTag paletteNbt = new ListTag();
            paletteNbt.add(singleTag);
            CompoundTag out = new CompoundTag();
            out.put("palette", paletteNbt);
            return out;
        }

        if (!(oldPal instanceof LinearPalette || oldPal instanceof HashMapPalette || oldPal instanceof GlobalPalette)) {
            return null;
        }

        Scratch scratch = SCRATCH_TL.get();
        int entryCount = strategy.entryCount();
        int[] buf = scratch.packBuf;
        if (buf.length < entryCount) {
            buf = new int[entryCount];
            scratch.packBuf = buf;
        }
        storage.unpack(buf);

        if (oldSize > 256 || oldPal instanceof GlobalPalette) {
            return encodeLargeOrGlobal(strategy, oldPal, buf, entryCount, scratch, isBlockState, useStrategyAccessor);
        }

        int[] remap = scratch.remapBuf;
        Arrays.fill(remap, 0, oldSize, 0);
        int usedCount = 0;
        for (int i = 0; i < entryCount; i++) {
            int oldId = buf[i];
            if (oldId < 0 || oldId >= oldSize) {
                return null;
            }
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
            Tag singleTag = encodeEntry(oldPal.valueFor(singleId), isBlockState);
            if (singleTag == null) {
                return null;
            }
            ListTag paletteNbt = new ListTag();
            paletteNbt.add(singleTag);
            CompoundTag out = new CompoundTag();
            out.put("palette", paletteNbt);
            return out;
        }

        Tag[] entryTags = scratch.entryTags;
        int writtenTags = 0;
        if (usedCount == oldSize) {
            for (int id = 0; id < oldSize; id++) {
                Tag t = encodeEntry(oldPal.valueFor(id), isBlockState);
                if (t == null) {
                    Arrays.fill(entryTags, 0, writtenTags, null);
                    return null;
                }
                entryTags[writtenTags++] = t;
            }
        } else {
            int nextId = 0;
            for (int oldId = 0; oldId < oldSize; oldId++) {
                if (remap[oldId] != 0) {
                    Tag t = encodeEntry(oldPal.valueFor(oldId), isBlockState);
                    if (t == null) {
                        Arrays.fill(entryTags, 0, writtenTags, null);
                        return null;
                    }
                    entryTags[nextId] = t;
                    writtenTags = nextId + 1;
                    remap[oldId] = nextId++;
                }
            }
        }

        ListTag paletteNbt = new ListTag();
        for (int i = 0; i < usedCount; i++) {
            paletteNbt.add(entryTags[i]);
            entryTags[i] = null;
        }

        Configuration cfg = (useStrategyAccessor && (Object) strategy instanceof StrategyAccessor sAcc)
                ? sAcc.chunking$getConfigForPaletteSize(usedCount)
                : ReflectFallback.getConfigForPaletteSize(strategy, usedCount);
        int bits = cfg.bitsInStorage();

        long[] dataArray = null;
        if (bits > 0) {
            if (usedCount == oldSize) {
                if (bits == storage.getBits() && storage instanceof SimpleBitStorage) {
                    dataArray = storage.getRaw().clone();
                } else {
                    dataArray = packBitsDirect(bits, entryCount, buf);
                }
            } else {
                for (int i = 0; i < entryCount; i++) {
                    buf[i] = remap[buf[i]];
                }
                dataArray = packBitsDirect(bits, entryCount, buf);
            }
        }

        CompoundTag out = new CompoundTag();
        out.put("palette", paletteNbt);
        if (dataArray != null) {
            out.putLongArray("data", dataArray);
        }
        return out;
    }

    private static <T> CompoundTag encodeLargeOrGlobal(
            Strategy<T> strategy,
            Palette<T> oldPal,
            int[] buf,
            int entryCount,
            Scratch scratch,
            boolean isBlockState,
            boolean useStrategyAccessor
    ) {
        Int2IntOpenHashMap map = scratch.globalRemap;
        map.clear();
        int[] usedOldIds = scratch.usedOldIds;
        if (usedOldIds.length < entryCount) {
            usedOldIds = new int[entryCount];
            scratch.usedOldIds = usedOldIds;
        }
        int usedCount = 0;
        int lastOld = -1;
        int lastNew = -1;
        for (int i = 0; i < entryCount; i++) {
            int oldId = buf[i];
            int newId;
            if (oldId == lastOld) {
                newId = lastNew;
            } else {
                newId = map.get(oldId);
                if (newId < 0) {
                    newId = usedCount++;
                    usedOldIds[newId] = oldId;
                    map.put(oldId, newId);
                }
                lastOld = oldId;
                lastNew = newId;
            }
            buf[i] = newId;
        }

        ListTag paletteNbt = new ListTag();
        for (int i = 0; i < usedCount; i++) {
            Tag t = encodeEntry(oldPal.valueFor(usedOldIds[i]), isBlockState);
            if (t == null) {
                return null;
            }
            paletteNbt.add(t);
        }

        CompoundTag out = new CompoundTag();
        out.put("palette", paletteNbt);
        if (usedCount > 1) {
            Configuration cfg = (useStrategyAccessor && (Object) strategy instanceof StrategyAccessor sAcc)
                    ? sAcc.chunking$getConfigForPaletteSize(usedCount)
                    : ReflectFallback.getConfigForPaletteSize(strategy, usedCount);
            int bits = cfg.bitsInStorage();
            if (bits > 0) {
                out.putLongArray("data", packBitsDirect(bits, entryCount, buf));
            }
        }
        return out;
    }

    /** Packs non-spanning {@code bits}-wide values into a raw {@code long[]} matching {@link SimpleBitStorage}. */
    private static long[] packBitsDirect(int bits, int entryCount, int[] values) {
        int valuesPerLong = 64 / bits;
        int len = (entryCount + valuesPerLong - 1) / valuesPerLong;
        long[] data = new long[len];
        long mask = (1L << bits) - 1L;
        int idx = 0;
        for (int word = 0; word < len; word++) {
            long acc = 0L;
            int shift = 0;
            int limit = Math.min(valuesPerLong, entryCount - idx);
            for (int k = 0; k < limit; k++) {
                acc |= ((long) values[idx++] & mask) << shift;
                shift += bits;
            }
            data[word] = acc;
        }
        return data;
    }

    private static final class ReflectFallback {
        private static final Field STRATEGY_FIELD;
        private static final Field DATA_FIELD;
        private static final Field STORAGE_FIELD;
        private static final Field PALETTE_FIELD;
        private static final Method CONFIG_FOR_PALETTE_SIZE_METHOD;

        static {
            try {
                STRATEGY_FIELD = PalettedContainer.class.getDeclaredField("strategy");
                STRATEGY_FIELD.setAccessible(true);
                DATA_FIELD = PalettedContainer.class.getDeclaredField("data");
                DATA_FIELD.setAccessible(true);
                Class<?> dataClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
                STORAGE_FIELD = dataClass.getDeclaredField("storage");
                STORAGE_FIELD.setAccessible(true);
                PALETTE_FIELD = dataClass.getDeclaredField("palette");
                PALETTE_FIELD.setAccessible(true);
                CONFIG_FOR_PALETTE_SIZE_METHOD = Strategy.class.getDeclaredMethod("getConfigurationForPaletteSize", int.class);
                CONFIG_FOR_PALETTE_SIZE_METHOD.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize PalettedContainer reflection fallback", e);
            }
        }

        @SuppressWarnings("unchecked")
        static <T> Strategy<T> getStrategy(PalettedContainer<T> container) {
            try {
                return (Strategy<T>) STRATEGY_FIELD.get(container);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static Object getData(PalettedContainer<?> container) {
            try {
                return DATA_FIELD.get(container);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static BitStorage getStorage(Object data) {
            try {
                return (BitStorage) STORAGE_FIELD.get(data);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("unchecked")
        static <T> Palette<T> getPalette(Object data) {
            try {
                return (Palette<T>) PALETTE_FIELD.get(data);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        static Configuration getConfigForPaletteSize(Strategy<?> strategy, int size) {
            try {
                return (Configuration) CONFIG_FOR_PALETTE_SIZE_METHOD.invoke(strategy, size);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Tag encodeEntry(Object entry, boolean isBlockState) {
        if (isBlockState) {
            return getBlockStateTag((BlockState) entry);
        }
        return getBiomeTag((Holder<Biome>) entry);
    }
}
