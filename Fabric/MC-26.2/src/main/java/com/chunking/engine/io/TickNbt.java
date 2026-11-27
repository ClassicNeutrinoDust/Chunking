package com.chunking.engine.io;

import net.minecraft.core.BlockPos;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct zero-DFU-Codec serializer/deserializer for {@link SavedTick} ({@code block_ticks} /
 * {@code fluid_ticks}) and {@link ChunkStatus} inside {@code SerializableChunkData}.
 *
 * <p>Eliminates ~28 GB of {@code DataResult}, {@code RecordBuilder}, {@code NbtOps}, and
 * {@code Enum.values().clone()} garbage per sustained worldgen run while falling back cleanly
 * ({@code null}) on any non-standard or unknown registry entry.
 */
public final class TickNbt {
    private static final int MAX_ID_CACHE = 4096;

    private static final TickPriority[] PRIORITY_BY_OFFSET = {
            TickPriority.EXTREMELY_HIGH, // -3
            TickPriority.VERY_HIGH,      // -2
            TickPriority.HIGH,           // -1
            TickPriority.NORMAL,         //  0
            TickPriority.LOW,            //  1
            TickPriority.VERY_LOW,       //  2
            TickPriority.EXTREMELY_LOW   //  3
    };

    private static final ConcurrentHashMap<Block, StringTag> BLOCK_TO_TAG = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<String, Block> TAG_TO_BLOCK = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<Fluid, StringTag> FLUID_TO_TAG = new ConcurrentHashMap<>(32);
    private static final ConcurrentHashMap<String, Fluid> TAG_TO_FLUID = new ConcurrentHashMap<>(32);
    private static final ConcurrentHashMap<String, ChunkStatus> STATUS_BY_NAME = new ConcurrentHashMap<>(32);

    private TickNbt() {}

    public static TickPriority priorityByValue(int value) {
        if (value <= -3) {
            return TickPriority.EXTREMELY_HIGH;
        }
        if (value >= 3) {
            return TickPriority.EXTREMELY_LOW;
        }
        return PRIORITY_BY_OFFSET[value + 3];
    }

    private static <T> StringTag encodeKey(T value, DefaultedRegistry<T> registry, ConcurrentHashMap<T, StringTag> cache) {
        StringTag cached = cache.get(value);
        if (cached != null) {
            return cached;
        }
        Identifier id = registry.getKey(value);
        if (id == null) {
            return null;
        }
        StringTag tag = StringTag.valueOf(id.toString());
        if (cache.size() < MAX_ID_CACHE) {
            cache.putIfAbsent(value, tag);
        }
        return tag;
    }

    private static <T> T decodeKey(String name, DefaultedRegistry<T> registry, ConcurrentHashMap<String, T> cache) {
        T cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Identifier id = Identifier.tryParse(name);
        if (id == null) {
            return null;
        }
        // Use getOptional (never getValue on DefaultedRegistry) so unknown IDs fall back to DFU.
        T value = registry.getOptional(id).orElse(null);
        if (value != null && cache.size() < MAX_ID_CACHE) {
            cache.putIfAbsent(name, value);
        }
        return value;
    }

    public static ListTag encodeBlockTicks(List<?> ticks) {
        return encodeTicks(ticks, Block.class, BuiltInRegistries.BLOCK, BLOCK_TO_TAG);
    }

    public static ListTag encodeFluidTicks(List<?> ticks) {
        return encodeTicks(ticks, Fluid.class, BuiltInRegistries.FLUID, FLUID_TO_TAG);
    }

    private static <T> ListTag encodeTicks(
            List<?> ticks,
            Class<T> typeClass,
            DefaultedRegistry<T> registry,
            ConcurrentHashMap<T, StringTag> cache
    ) {
        if (ticks == null) {
            return null;
        }
        if (ticks.isEmpty()) {
            return new ListTag();
        }
        ListTag out = new ListTag();
        for (int i = 0, n = ticks.size(); i < n; i++) {
            if (!(ticks.get(i) instanceof SavedTick<?> tick) || !typeClass.isInstance(tick.type())) {
                return null;
            }
            StringTag idTag = encodeKey(typeClass.cast(tick.type()), registry, cache);
            BlockPos pos = tick.pos();
            TickPriority priority = tick.priority();
            if (idTag == null || pos == null || priority == null) {
                return null;
            }
            CompoundTag tag = new CompoundTag();
            tag.put("i", idTag);
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putInt("t", tick.delay());
            tag.putInt("p", priority.getValue());
            out.add(tag);
        }
        return out;
    }

    public static Optional<List<SavedTick<Block>>> decodeBlockTicks(CompoundTag chunkTag, String key) {
        return decodeTicks(chunkTag, key, BuiltInRegistries.BLOCK, TAG_TO_BLOCK);
    }

    public static Optional<List<SavedTick<Fluid>>> decodeFluidTicks(CompoundTag chunkTag, String key) {
        return decodeTicks(chunkTag, key, BuiltInRegistries.FLUID, TAG_TO_FLUID);
    }

    private static <T> Optional<List<SavedTick<T>>> decodeTicks(
            CompoundTag chunkTag,
            String key,
            DefaultedRegistry<T> registry,
            ConcurrentHashMap<String, T> cache
    ) {
        if (chunkTag == null || !chunkTag.contains(key)) {
            return Optional.empty();
        }
        if (!(chunkTag.get(key) instanceof ListTag list)) {
            return null;
        }
        int n = list.size();
        if (n == 0) {
            return Optional.of(List.of());
        }
        List<SavedTick<T>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!(list.get(i) instanceof CompoundTag entry)) {
                return null;
            }
            String name = entry.getStringOr("i", null);
            T type = name != null ? decodeKey(name, registry, cache) : null;
            if (type == null) {
                return null;
            }
            if (!(entry.get("x") instanceof IntTag ix
                    && entry.get("y") instanceof IntTag iy
                    && entry.get("z") instanceof IntTag iz
                    && entry.get("t") instanceof IntTag it
                    && entry.get("p") instanceof IntTag ip)) {
                return null;
            }
            out.add(new SavedTick<>(
                    type,
                    new BlockPos(ix.value(), iy.value(), iz.value()),
                    it.value(),
                    priorityByValue(ip.value())
            ));
        }
        return Optional.of(out);
    }

    public static Optional<ChunkStatus> decodeChunkStatus(CompoundTag chunkTag, String key) {
        if (chunkTag == null || !chunkTag.contains(key)) {
            return Optional.empty();
        }
        String name = chunkTag.getStringOr(key, null);
        if (name == null) {
            return null;
        }
        ChunkStatus cached = STATUS_BY_NAME.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        Identifier id = Identifier.tryParse(name);
        if (id == null) {
            return null;
        }
        ChunkStatus status = BuiltInRegistries.CHUNK_STATUS.getOptional(id).orElse(null);
        if (status == null) {
            return null;
        }
        STATUS_BY_NAME.putIfAbsent(name, status);
        return Optional.of(status);
    }
}
