package com.chunking.engine.io;

import com.chunking.engine.mixin.accessor.PalettedContainerInvoker;
import com.chunking.engine.mixin.accessor.StrategyAccessor;
import com.chunking.engine.access.PalettedContainerAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct zero-DFU-Codec NBT decoder for {@code SerializableChunkData.parse()} section
 * {@code "block_states"} and {@code "biomes"} {@link PalettedContainer}s.
 *
 * <p>Scales blockstate cache bounds dynamically from {@link Block#BLOCK_STATE_REGISTRY} and
 * handles repacked {@code GlobalPalette} sections without falling back to DFU {@code Codec.parse}.
 */
public final class SectionReader {
    private static final int MIN_SIMPLE_CACHE = 16_384;
    private static final int MIN_PROPS_CACHE = 32_768;
    private static final int MAX_SOFT_CACHE = 262_144;
    private static final boolean MIXIN_ACTIVE = PalettedContainerAccess.class.isAssignableFrom(PalettedContainer.class);
    private static final ConcurrentHashMap<String, BlockState> SIMPLE_BLOCK_CACHE = new ConcurrentHashMap<>(1024);
    private static final ConcurrentHashMap<CompoundTag, BlockState> PROPS_BLOCK_CACHE = new ConcurrentHashMap<>(2048);
    private static final ThreadLocal<ArrayList<Object>> PALETTE_SCRATCH_TL =
            ThreadLocal.withInitial(() -> new ArrayList<>(32));
    private static final ThreadLocal<int[]> REPACK_SCRATCH_TL = ThreadLocal.withInitial(() -> new int[4096]);

    private record BiomeLookupCache(Strategy<Holder<Biome>> strategy, Map<String, Holder<Biome>> map) {}

    private static final Object BIOME_CACHE_LOCK = new Object();
    private static final Map<Strategy<Holder<Biome>>, Map<String, Holder<Biome>>> BIOME_MAPS = new WeakHashMap<>();
    private static volatile BiomeLookupCache lastBiomeCache;

    public static void clearBiomeCache() {
        synchronized (BIOME_CACHE_LOCK) {
            BIOME_MAPS.clear();
            lastBiomeCache = null;
        }
    }

    private SectionReader() {}

    private static int simpleCacheLimit() {
        try {
            return Math.max(MIN_SIMPLE_CACHE, Math.min(MAX_SOFT_CACHE, BuiltInRegistries.BLOCK.size() * 2));
        } catch (Throwable ignored) {
            return MIN_SIMPLE_CACHE;
        }
    }

    private static int propsCacheLimit() {
        try {
            return Math.max(MIN_PROPS_CACHE, Math.min(MAX_SOFT_CACHE, Block.BLOCK_STATE_REGISTRY.size() * 2));
        } catch (Throwable ignored) {
            return MIN_PROPS_CACHE;
        }
    }

    public static BlockState decodeBlockStateEntry(CompoundTag entry) {
        if (entry == null) {
            return null;
        }
        if (entry.get("Properties") == null) {
            String name = entry.getStringOr("Name", null);
            if (name == null) {
                return null;
            }
            BlockState cached = SIMPLE_BLOCK_CACHE.get(name);
            if (cached != null) {
                return cached;
            }
            Identifier id = Identifier.tryParse(name);
            if (id == null) {
                return null;
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block == null) {
                return null;
            }
            BlockState def = block.defaultBlockState();
            if (SIMPLE_BLOCK_CACHE.size() < simpleCacheLimit()) {
                SIMPLE_BLOCK_CACHE.putIfAbsent(name, def);
            }
            return def;
        }
        BlockState cached = PROPS_BLOCK_CACHE.get(entry);
        if (cached != null) {
            return cached;
        }
        BlockState parsed = BlockState.CODEC.parse(NbtOps.INSTANCE, entry).result().orElse(null);
        if (parsed != null && PROPS_BLOCK_CACHE.size() < propsCacheLimit()) {
            PROPS_BLOCK_CACHE.putIfAbsent(entry.copy(), parsed);
        }
        return parsed;
    }

    private static Map<String, Holder<Biome>> getBiomeLookup(Strategy<Holder<Biome>> strategy) {
        if (strategy == null) {
            return null;
        }
        BiomeLookupCache cached = lastBiomeCache;
        if (cached != null && cached.strategy() == strategy) {
            return cached.map();
        }
        synchronized (BIOME_CACHE_LOCK) {
            Map<String, Holder<Biome>> map = BIOME_MAPS.get(strategy);
            if (map == null) {
                HashMap<String, Holder<Biome>> built = new HashMap<>(128);
                for (Holder<Biome> holder : strategy.globalMap()) {
                    var keyOpt = holder.unwrapKey();
                    if (keyOpt.isPresent()) {
                        built.put(keyOpt.get().identifier().toString(), holder);
                    }
                }
                map = built;
                BIOME_MAPS.put(strategy, map);
            }
            lastBiomeCache = new BiomeLookupCache(strategy, map);
            return map;
        }
    }

    public static PalettedContainer<BlockState> decodeBlockStates(CompoundTag tag, Strategy<BlockState> strategy) {
        return decodeBlockStates(tag, strategy, false);
    }

    @SuppressWarnings("unchecked")
    public static PalettedContainer<BlockState> decodeBlockStates(
            CompoundTag tag,
            Strategy<BlockState> strategy,
            boolean zeroCopyRaw
    ) {
        if (tag == null || strategy == null || !(tag.get("palette") instanceof ListTag palTag)) {
            return null;
        }
        int palSize = palTag.size();
        if (palSize <= 0 || palSize > strategy.entryCount()) {
            return null;
        }
        ArrayList<Object> scratch = PALETTE_SCRATCH_TL.get();
        scratch.clear();
        try {
            for (int i = 0; i < palSize; i++) {
                if (!(palTag.get(i) instanceof CompoundTag entryTag)) {
                    return null;
                }
                BlockState state = decodeBlockStateEntry(entryTag);
                if (state == null) {
                    return null;
                }
                scratch.add(state);
            }
            return buildContainer(strategy, (List<BlockState>) (List<?>) scratch, tag, zeroCopyRaw);
        } catch (Throwable ignored) {
            return null;
        } finally {
            scratch.clear();
        }
    }

    public static PalettedContainerRO<Holder<Biome>> decodeBiomes(CompoundTag tag, Strategy<Holder<Biome>> strategy) {
        return decodeBiomes(tag, strategy, false);
    }

    @SuppressWarnings("unchecked")
    public static PalettedContainerRO<Holder<Biome>> decodeBiomes(
            CompoundTag tag,
            Strategy<Holder<Biome>> strategy,
            boolean zeroCopyRaw
    ) {
        if (tag == null || strategy == null || !(tag.get("palette") instanceof ListTag palTag)) {
            return null;
        }
        int palSize = palTag.size();
        if (palSize <= 0 || palSize > strategy.entryCount()) {
            return null;
        }
        Map<String, Holder<Biome>> biomeMap = getBiomeLookup(strategy);
        if (biomeMap == null) {
            return null;
        }
        ArrayList<Object> scratch = PALETTE_SCRATCH_TL.get();
        scratch.clear();
        try {
            for (int i = 0; i < palSize; i++) {
                if (!(palTag.get(i) instanceof StringTag strTag)) {
                    return null;
                }
                Holder<Biome> holder = biomeMap.get(strTag.value());
                if (holder == null) {
                    return null;
                }
                scratch.add(holder);
            }
            return buildContainer(strategy, (List<Holder<Biome>>) (List<?>) scratch, tag, zeroCopyRaw);
        } catch (Throwable ignored) {
            return null;
        } finally {
            scratch.clear();
        }
    }

    private static <T> PalettedContainer<T> buildContainer(
            Strategy<T> strategy,
            List<T> entries,
            CompoundTag tag,
            boolean zeroCopyRaw
    ) {
        int palSize = entries.size();
        Configuration cfg = MIXIN_ACTIVE
                ? ((StrategyAccessor) (Object) strategy).chunking$getConfigForPaletteSize(palSize)
                : ReflectFallback.getConfigForPaletteSize(strategy, palSize);
        int bitsInMem = cfg.bitsInMemory();
        int bitsInStorage = cfg.bitsInStorage();
        int entryCount = strategy.entryCount();
        if (bitsInMem == 0) {
            Palette<T> palette = cfg.createPalette(strategy, entries);
            return createContainer(strategy, cfg, new ZeroBitStorage(entryCount), palette);
        }
        if (bitsInStorage <= 0 || !(tag.get("data") instanceof LongArrayTag lat)) {
            return null;
        }
        long[] raw = lat.getAsLongArray();
        int valuesPerLong = 64 / bitsInStorage;
        int expectedLongs = (entryCount + valuesPerLong - 1) / valuesPerLong;
        if (raw.length != expectedLongs) {
            return null;
        }
        Palette<T> palette = cfg.createPalette(strategy, entries);
        if (cfg.alwaysRepack() || bitsInMem != bitsInStorage) {
            SimpleBitStorage diskStorage = new SimpleBitStorage(bitsInStorage, entryCount, raw);
            int[] buf = REPACK_SCRATCH_TL.get();
            if (buf.length < entryCount) {
                buf = new int[entryCount];
                REPACK_SCRATCH_TL.set(buf);
            }
            diskStorage.unpack(buf);
            int[] diskToMem = new int[palSize];
            PaletteResize<T> noResize = PaletteResize.noResizeExpected();
            for (int i = 0; i < palSize; i++) {
                diskToMem[i] = palette.idFor(entries.get(i), noResize);
            }
            for (int i = 0; i < entryCount; i++) {
                int diskId = buf[i];
                if (diskId < 0 || diskId >= palSize) {
                    return null;
                }
                buf[i] = diskToMem[diskId];
            }
            long[] memRaw = packBitsDirect(bitsInMem, entryCount, buf);
            return createContainer(strategy, cfg, new SimpleBitStorage(bitsInMem, entryCount, memRaw), palette);
        }
        BitStorage storage = new SimpleBitStorage(bitsInStorage, entryCount, zeroCopyRaw ? raw : raw.clone());
        return createContainer(strategy, cfg, storage, palette);
    }

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

    private static <T> PalettedContainer<T> createContainer(
            Strategy<T> strategy,
            Configuration cfg,
            BitStorage storage,
            Palette<T> palette
    ) {
        if (MIXIN_ACTIVE) {
            return PalettedContainerInvoker.chunking$create(strategy, cfg, storage, palette);
        }
        return ReflectFallback.create(strategy, cfg, storage, palette);
    }

    private static final class ReflectFallback {
        private static final Method CONFIG_FOR_PALETTE_SIZE_METHOD;
        @SuppressWarnings("rawtypes")
        private static final Constructor<PalettedContainer> RAW_CTOR;

        static {
            try {
                CONFIG_FOR_PALETTE_SIZE_METHOD = Strategy.class.getDeclaredMethod("getConfigurationForPaletteSize", int.class);
                CONFIG_FOR_PALETTE_SIZE_METHOD.setAccessible(true);
                RAW_CTOR = PalettedContainer.class.getDeclaredConstructor(
                        Strategy.class, Configuration.class, BitStorage.class, Palette.class);
                RAW_CTOR.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to initialize PalettedContainer reflection fallback", e);
            }
        }

        static Configuration getConfigForPaletteSize(Strategy<?> strategy, int size) {
            try {
                return (Configuration) CONFIG_FOR_PALETTE_SIZE_METHOD.invoke(strategy, size);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings("unchecked")
        static <T> PalettedContainer<T> create(
                Strategy<T> strategy,
                Configuration cfg,
                BitStorage storage,
                Palette<T> palette
        ) {
            try {
                return RAW_CTOR.newInstance(strategy, cfg, storage, palette);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
