package com.chunking.engine.io;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 3-tier bounded per-dimension cache of recently serialized {@link SerializableChunkData} snapshots.
 * Separates tiny early-status {@code ProtoChunk} snapshots ({@code lightProtoMap}, {@code <= STRUCTURE_REFERENCES}),
 * heavier partial {@code ProtoChunk} snapshots ({@code heavyProtoMap}, {@code BIOMES..INITIALIZE_LIGHT}), and completed
 * {@code FULL} chunk snapshots ({@code fullMap}) under independent locks using primitive-key FastUtil LRU maps.
 * Dynamically tightens cache bounds and Old Gen eviction thresholds when active client rendering is detected
 * (4 GB {@code runClient} heaps) while preserving full capacities on dedicated servers.
 */
public final class RecentChunks {
    public record Entry(SerializableChunkData data, CompletableFuture<Void> serializeDone) {}

    public record LoadResult(
            SerializableChunkData data,
            CompletableFuture<Void> serializeDone,
            boolean cloneRequired
    ) {}

    private static final MemoryPoolMXBean OLD_GEN_POOL = findOldGenPool();
    private static final int MAX_HEAVY_PROTO_CAP = 384;
    private static final int MAX_FULL_CAP = 128;
    private static final int CLIENT_LIGHT_PROTO_CAP = 1024;
    private static final int CLIENT_HEAVY_PROTO_CAP = 96;
    private static final int CLIENT_FULL_CAP = 32;

    private final int maxLightProtoEntries;
    private final int maxHeavyProtoEntries;
    private final int maxFullEntries;
    private volatile int effectiveLightProtoCap;
    private volatile int effectiveHeavyProtoCap;
    private volatile int effectiveFullCap;
    private final Object lightProtoLock = new Object();
    private final Object protoLock = new Object();
    private final Object fullLock = new Object();
    private final Long2ObjectLinkedOpenHashMap<Entry> lightProtoMap;
    private final Long2ObjectLinkedOpenHashMap<Entry> heavyProtoMap;
    private final Long2ObjectLinkedOpenHashMap<Entry> fullMap;
    private volatile long lastPressureCheckNs;
    private volatile long lastEvictNs;
    private volatile int pressureLevel;

    public RecentChunks(int maxProtoEntries, int maxFullEntries) {
        this(maxProtoEntries, maxProtoEntries, maxFullEntries);
    }

    public RecentChunks(int maxLightProtoEntries, int maxHeavyProtoEntries, int maxFullEntries) {
        this.maxLightProtoEntries = Math.max(0, Math.min(16_384, maxLightProtoEntries));
        this.maxHeavyProtoEntries = Math.max(0, Math.min(MAX_HEAVY_PROTO_CAP, maxHeavyProtoEntries));
        this.maxFullEntries = Math.max(0, Math.min(MAX_FULL_CAP, maxFullEntries));
        this.effectiveLightProtoCap = this.maxLightProtoEntries;
        this.effectiveHeavyProtoCap = this.maxHeavyProtoEntries;
        this.effectiveFullCap = this.maxFullEntries;
        this.lightProtoMap = this.maxLightProtoEntries > 0
                ? new Long2ObjectLinkedOpenHashMap<>(Math.min(1024, this.maxLightProtoEntries), 0.75F)
                : null;
        this.heavyProtoMap = this.maxHeavyProtoEntries > 0
                ? new Long2ObjectLinkedOpenHashMap<>(Math.min(256, this.maxHeavyProtoEntries), 0.75F)
                : null;
        this.fullMap = this.maxFullEntries > 0
                ? new Long2ObjectLinkedOpenHashMap<>(Math.min(128, this.maxFullEntries), 0.75F)
                : null;
    }

    private static MemoryPoolMXBean findOldGenPool() {
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    String lower = pool.getName().toLowerCase(Locale.ROOT);
                    if (lower.contains("old") || lower.contains("tenured")) {
                        return pool;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void updateEffectiveCaps(boolean clientActive) {
        int nextLight = clientActive
                ? Math.min(this.maxLightProtoEntries, CLIENT_LIGHT_PROTO_CAP)
                : this.maxLightProtoEntries;
        int nextHeavy = clientActive
                ? Math.min(this.maxHeavyProtoEntries, CLIENT_HEAVY_PROTO_CAP)
                : this.maxHeavyProtoEntries;
        int nextFull = clientActive
                ? Math.min(this.maxFullEntries, CLIENT_FULL_CAP)
                : this.maxFullEntries;
        boolean tightened = nextLight < this.effectiveLightProtoCap
                || nextHeavy < this.effectiveHeavyProtoCap
                || nextFull < this.effectiveFullCap;
        this.effectiveLightProtoCap = nextLight;
        this.effectiveHeavyProtoCap = nextHeavy;
        this.effectiveFullCap = nextFull;
        if (tightened) {
            if (this.fullMap != null) {
                synchronized (this.fullLock) {
                    trimToCap(this.fullMap, nextFull);
                }
            }
            if (this.heavyProtoMap != null) {
                synchronized (this.protoLock) {
                    trimToCap(this.heavyProtoMap, nextHeavy);
                }
            }
            if (this.lightProtoMap != null) {
                synchronized (this.lightProtoLock) {
                    trimToCap(this.lightProtoMap, nextLight);
                }
            }
        }
    }

    private static void trimToCap(Long2ObjectLinkedOpenHashMap<Entry> map, int cap) {
        while (map.size() > cap) {
            map.removeLast();
        }
    }

    private void checkMemoryPressure() {
        long now = System.nanoTime();
        if (now - this.lastPressureCheckNs < 50_000_000L) {
            return;
        }
        this.lastPressureCheckNs = now;
        boolean clientActive = com.chunking.engine.scheduler.FrameGuard.get().hasActiveClientFrames();
        updateEffectiveCaps(clientActive);
        long maxHeap = Runtime.getRuntime().maxMemory();
        MemoryPoolMXBean oldPool = OLD_GEN_POOL;
        if (oldPool != null) {
            MemoryUsage usage = oldPool.getUsage();
            long oldUsed = usage != null ? usage.getUsed() : 0L;
            long oldMax = (usage != null && usage.getMax() > 0L) ? Math.min(maxHeap, usage.getMax()) : maxHeap;
            boolean isZgc = oldPool.getName().toLowerCase(java.util.Locale.ROOT).contains("zgc");
            long critPct = isZgc ? 90L : (clientActive ? 70L : 82L);
            long highPct = isZgc ? 84L : (clientActive ? 60L : 72L);
            long warnPct = isZgc ? 78L : (clientActive ? 48L : 60L);
            if (oldUsed > (oldMax * critPct) / 100L) {
                this.pressureLevel = 2;
                if (now - this.lastEvictNs >= 250_000_000L) {
                    this.lastEvictNs = now;
                    synchronized (this.fullLock) {
                        evictFraction(this.fullMap, 4);
                    }
                    synchronized (this.protoLock) {
                        evictFraction(this.heavyProtoMap, 4);
                    }
                    synchronized (this.lightProtoLock) {
                        evictFraction(this.lightProtoMap, 4);
                    }
                }
            } else if (oldUsed > (oldMax * highPct) / 100L) {
                this.pressureLevel = 1;
                if (now - this.lastEvictNs >= 250_000_000L) {
                    this.lastEvictNs = now;
                    synchronized (this.fullLock) {
                        evictFraction(this.fullMap, 4);
                    }
                    synchronized (this.protoLock) {
                        evictFraction(this.heavyProtoMap, 6);
                    }
                }
            } else if (oldUsed > (oldMax * warnPct) / 100L) {
                this.pressureLevel = 0;
                if (now - this.lastEvictNs >= 250_000_000L) {
                    this.lastEvictNs = now;
                    synchronized (this.fullLock) {
                        evictFraction(this.fullMap, 6);
                    }
                }
            } else {
                this.pressureLevel = 0;
            }
            return;
        }

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long critPct = clientActive ? 78L : 88L;
        long highPct = clientActive ? 68L : 80L;
        if (used > (maxHeap * critPct) / 100L) {
            this.pressureLevel = 2;
            if (now - this.lastEvictNs >= 250_000_000L) {
                this.lastEvictNs = now;
                synchronized (this.fullLock) {
                    evictFraction(this.fullMap, 4);
                }
                synchronized (this.protoLock) {
                    evictFraction(this.heavyProtoMap, 4);
                }
                synchronized (this.lightProtoLock) {
                    evictFraction(this.lightProtoMap, 4);
                }
            }
        } else if (used > (maxHeap * highPct) / 100L) {
            this.pressureLevel = 1;
            if (now - this.lastEvictNs >= 250_000_000L) {
                this.lastEvictNs = now;
                synchronized (this.fullLock) {
                    evictFraction(this.fullMap, 4);
                }
            }
        } else {
            this.pressureLevel = 0;
        }
    }

    public void put(long packedPos, SerializableChunkData data, CompletableFuture<Void> serializeDone) {
        if (data == null || serializeDone == null
                || (this.lightProtoMap == null && this.heavyProtoMap == null && this.fullMap == null)) {
            return;
        }
        checkMemoryPressure();
        ChunkStatus status = data.chunkStatus();
        boolean isFull = status == ChunkStatus.FULL;
        int pressure = this.pressureLevel;
        if (pressure >= 2 || (isFull && pressure >= 1)) {
            invalidate(packedPos);
            return;
        }

        Entry entry = new Entry(data, serializeDone);
        if (isFull) {
            if (this.lightProtoMap != null) {
                synchronized (this.lightProtoLock) {
                    this.lightProtoMap.remove(packedPos);
                }
            }
            if (this.heavyProtoMap != null) {
                synchronized (this.protoLock) {
                    this.heavyProtoMap.remove(packedPos);
                }
            }
            if (this.fullMap != null) {
                int cap = this.effectiveFullCap;
                synchronized (this.fullLock) {
                    this.fullMap.putAndMoveToFirst(packedPos, entry);
                    while (this.fullMap.size() > cap) {
                        this.fullMap.removeLast();
                    }
                }
            }
        } else if (status.isOrBefore(ChunkStatus.STRUCTURE_REFERENCES)) {
            if (this.fullMap != null) {
                synchronized (this.fullLock) {
                    this.fullMap.remove(packedPos);
                }
            }
            if (this.heavyProtoMap != null) {
                synchronized (this.protoLock) {
                    this.heavyProtoMap.remove(packedPos);
                }
            }
            if (this.lightProtoMap != null) {
                int cap = this.effectiveLightProtoCap;
                synchronized (this.lightProtoLock) {
                    this.lightProtoMap.putAndMoveToFirst(packedPos, entry);
                    while (this.lightProtoMap.size() > cap) {
                        this.lightProtoMap.removeLast();
                    }
                }
            }
        } else {
            if (this.fullMap != null) {
                synchronized (this.fullLock) {
                    this.fullMap.remove(packedPos);
                }
            }
            if (this.lightProtoMap != null) {
                synchronized (this.lightProtoLock) {
                    this.lightProtoMap.remove(packedPos);
                }
            }
            if (this.heavyProtoMap != null) {
                int cap = this.effectiveHeavyProtoCap;
                synchronized (this.protoLock) {
                    this.heavyProtoMap.putAndMoveToFirst(packedPos, entry);
                    while (this.heavyProtoMap.size() > cap) {
                        this.heavyProtoMap.removeLast();
                    }
                }
            }
        }
    }

    private static void evictFraction(Long2ObjectLinkedOpenHashMap<Entry> map, int divisor) {
        if (map == null || map.isEmpty()) {
            return;
        }
        int toEvict = Math.max(1, map.size() / divisor);
        while (toEvict-- > 0 && !map.isEmpty()) {
            map.removeLast();
        }
    }

    /** Returns 1 if cached as FULL, -1 if cached as partial ProtoChunk, 0 if absent. */
    public int peekChunkType(long packedPos) {
        if (this.lightProtoMap == null && this.heavyProtoMap == null && this.fullMap == null) {
            return 0;
        }
        if (this.fullMap != null) {
            synchronized (this.fullLock) {
                if (this.fullMap.containsKey(packedPos)) {
                    return 1;
                }
            }
        }
        if (this.heavyProtoMap != null) {
            synchronized (this.protoLock) {
                if (this.heavyProtoMap.containsKey(packedPos)) {
                    return -1;
                }
            }
        }
        if (this.lightProtoMap != null) {
            synchronized (this.lightProtoLock) {
                if (this.lightProtoMap.containsKey(packedPos)) {
                    return -1;
                }
            }
        }
        return 0;
    }

    /** Atomically moves a cached snapshot out on load (one-shot ownership). */
    public LoadResult acquireForLoad(long packedPos) {
        if (this.lightProtoMap == null && this.heavyProtoMap == null && this.fullMap == null) {
            return null;
        }
        if (this.lightProtoMap != null) {
            synchronized (this.lightProtoLock) {
                Entry proto = this.lightProtoMap.remove(packedPos);
                if (proto != null) {
                    return new LoadResult(proto.data(), proto.serializeDone(), false);
                }
            }
        }
        if (this.heavyProtoMap != null) {
            synchronized (this.protoLock) {
                Entry proto = this.heavyProtoMap.remove(packedPos);
                if (proto != null) {
                    return new LoadResult(proto.data(), proto.serializeDone(), false);
                }
            }
        }
        if (this.fullMap != null) {
            synchronized (this.fullLock) {
                Entry full = this.fullMap.remove(packedPos);
                if (full != null) {
                    return new LoadResult(full.data(), full.serializeDone(), false);
                }
            }
        }
        return null;
    }

    public void invalidate(long packedPos) {
        if (this.lightProtoMap != null) {
            synchronized (this.lightProtoLock) {
                this.lightProtoMap.remove(packedPos);
            }
        }
        if (this.heavyProtoMap != null) {
            synchronized (this.protoLock) {
                this.heavyProtoMap.remove(packedPos);
            }
        }
        if (this.fullMap != null) {
            synchronized (this.fullLock) {
                this.fullMap.remove(packedPos);
            }
        }
    }

    public void clear() {
        if (this.lightProtoMap != null) {
            synchronized (this.lightProtoLock) {
                this.lightProtoMap.clear();
            }
        }
        if (this.heavyProtoMap != null) {
            synchronized (this.protoLock) {
                this.heavyProtoMap.clear();
            }
        }
        if (this.fullMap != null) {
            synchronized (this.fullLock) {
                this.fullMap.clear();
            }
        }
    }
}
