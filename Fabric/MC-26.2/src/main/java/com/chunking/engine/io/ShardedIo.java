package com.chunking.engine.io;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.RawFrameReadAccess;
import com.chunking.engine.access.RegionStorageAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Region-sharded parallel disk I/O engine for dedicated servers and high-core clients.
 * <p>
 * Routes each chunk {@code (rx, rz)} deterministically to one of {@code S} independent shards
 * ({@code Math.floorMod(rx * 31 + rz, S)}). Because each {@code .mca} region file belongs to
 * exactly one shard, region reads, writes, pending-write maps, and frame caches execute in
 * parallel across {@code Chunking-ShardedIO-*} threads with zero cross-shard lock contention.
 */
public final class ShardedIo {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chunking");
    private static final int PRIO_FOREGROUND = 0;
    private static final int PRIO_BACKGROUND = 1;
    private static final int PRIO_SHUTDOWN = 2;

    private static final AtomicInteger THREAD_IDS = new AtomicInteger(1);
    private static final Object POOL_LOCK = new Object();
    private static volatile ExecutorService sharedIoPool;

    public static final class RawReadResult {
        public static final RawReadResult EMPTY = new RawReadResult(null, null);
        public final byte[] frame;
        public final CompoundTag tag;

        private RawReadResult(byte[] frame, CompoundTag tag) {
            this.frame = frame;
            this.tag = tag;
        }

        public boolean isEmpty() {
            return this.frame == null && this.tag == null;
        }

        public static RawReadResult ofFrame(byte[] frame) {
            return new RawReadResult(frame, null);
        }

        public static RawReadResult ofTag(CompoundTag tag) {
            return new RawReadResult(null, tag);
        }
    }

    private static final class PendingWrite {
        CompoundTag data;
        final CompletableFuture<Void> result = new CompletableFuture<>();

        PendingWrite(CompoundTag data) {
            this.data = data;
        }
    }

    private static final class Shard {
        final RegionFileStorage storage;
        final PriorityConsecutiveExecutor executor;
        final LinkedHashMap<ChunkPos, PendingWrite> pendingWrites = new LinkedHashMap<>();
        final FrameCache frameCache;
        boolean storeScheduled;
        boolean drainingPending;

        Shard(RegionFileStorage storage, PriorityConsecutiveExecutor executor, int frameCacheCap) {
            this.storage = storage;
            this.executor = executor;
            this.frameCache = frameCacheCap > 0 ? FrameCache.createUnsynchronized(frameCacheCap) : null;
        }
    }

    private final Shard[] shards;
    private final java.util.concurrent.locks.ReentrantReadWriteLock lifecycleLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile boolean closed;

    public ShardedIo(
            RegionStorageInfo info,
            Path folder,
            boolean sync,
            RegionFileStorage primaryStorage
    ) {
        int cpus = Runtime.getRuntime().availableProcessors();
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        boolean isClient = ModConfig.isClientEnvironment();
        boolean lowHeap = maxHeapMb <= 2560L;
        int maxShards = lowHeap ? 4 : 8;
        int count = Math.max(2, Math.min(maxShards, cpus / 4));
        int defaultFrameCap = isClient ? 128 : (lowHeap ? 512 : 2_048);
        int totalFrameCap = Integer.getInteger("chunking.io_frame_cache", defaultFrameCap);
        int minPerShardCap = (isClient || lowHeap) ? 32 : 128;
        int perShardCap = totalFrameCap > 0 ? Math.max(minPerShardCap, totalFrameCap / count) : 0;
        ExecutorService pool = sharedPool(count);
        this.shards = new Shard[count];
        for (int i = 0; i < count; i++) {
            RegionFileStorage storage = (i == 0) ? primaryStorage : new RegionFileStorage(info, folder, sync);
            PriorityConsecutiveExecutor exec = new PriorityConsecutiveExecutor(3, pool, info.type() + "-S" + i);
            this.shards[i] = new Shard(storage, exec, perShardCap);
        }
    }

    private static ExecutorService sharedPool(int minThreads) {
        ExecutorService exec = sharedIoPool;
        if (exec == null) {
            synchronized (POOL_LOCK) {
                exec = sharedIoPool;
                if (exec == null) {
                    int cpus = Runtime.getRuntime().availableProcessors();
                    int threads = Math.max(minThreads, Math.min(16, cpus / 4));
                    exec = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "Chunking-ShardedIO-" + THREAD_IDS.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    });
                    sharedIoPool = exec;
                }
            }
        }
        return exec;
    }

    public int shardCount() {
        return this.shards.length;
    }

    private Shard shardFor(int rx, int rz) {
        int idx = Math.floorMod(rx * 31 + rz, this.shards.length);
        return this.shards[idx];
    }

    public CompletableFuture<Void> store(
            ChunkPos pos,
            Supplier<CompoundTag> dataSupplier,
            AtomicBoolean shutdownRequested
    ) {
        Shard shard = shardFor(pos.getRegionX(), pos.getRegionZ());
        CompletableFuture<Void> outer = new CompletableFuture<>();
        this.lifecycleLock.readLock().lock();
        try {
            if (this.closed || shutdownRequested.get()) {
                return CompletableFuture.completedFuture(null);
            }
            shard.executor.schedule(new StrictQueue.RunnableWithPriority(PRIO_FOREGROUND, () -> {
            // Do NOT drop already-accepted writes if shutdownRequested flips before this
            // PRIO_FOREGROUND task executes; PRIO_SHUTDOWN drains after all PRIO_FOREGROUND tasks.
            try {
                CompoundTag tag = dataSupplier.get();
                PendingWrite existing = shard.pendingWrites.get(pos);
                if (existing != null) {
                    existing.data = tag;
                    existing.result.whenComplete((v, err) -> {
                        if (err != null) {
                            outer.completeExceptionally(err);
                        } else {
                            outer.complete(null);
                        }
                    });
                } else {
                    PendingWrite pw = new PendingWrite(tag);
                    shard.pendingWrites.put(pos, pw);
                    pw.result.whenComplete((v, err) -> {
                        if (err != null) {
                            outer.completeExceptionally(err);
                        } else {
                            outer.complete(null);
                        }
                    });
                }
                tellStorePending(shard, shutdownRequested);
            } catch (Throwable t) {
                outer.completeExceptionally(t);
            }
        }));
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
        return outer;
    }

    public CompletableFuture<RawReadResult> loadRawAsync(ChunkPos pos, AtomicBoolean shutdownRequested) {
        Shard shard = shardFor(pos.getRegionX(), pos.getRegionZ());
        CompletableFuture<RawReadResult> future = new CompletableFuture<>();
        this.lifecycleLock.readLock().lock();
        try {
            if (this.closed || shutdownRequested.get()) {
                return CompletableFuture.completedFuture(RawReadResult.EMPTY);
            }
            shard.executor.schedule(new StrictQueue.RunnableWithPriority(PRIO_FOREGROUND, () -> {
            if (shutdownRequested.get()) {
                future.complete(RawReadResult.EMPTY);
                return;
            }
            try {
                future.complete(readRawOnShardThread(shard, pos));
            } catch (Throwable t) {
                LOGGER.warn("Failed to read chunk {} on sharded I/O", pos, t);
                future.completeExceptionally(t);
            } finally {
                tellStorePending(shard, shutdownRequested);
            }
        }));
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
        return future;
    }

    private static RawReadResult readRawOnShardThread(Shard shard, ChunkPos pos) throws IOException {
        PendingWrite pending = shard.pendingWrites.get(pos);
        if (pending != null) {
            CompoundTag rawTag = pending.data;
            if (rawTag == null) {
                return RawReadResult.EMPTY;
            }
            byte[] preEncoded = EncodedCache.extractPreEncodedFrame(rawTag);
            if (preEncoded != null) {
                return RawReadResult.ofFrame(preEncoded);
            }
            return RawReadResult.ofTag(rawTag.copy());
        }
        long packed = pos.pack();
        if (shard.frameCache != null) {
            byte[] cached = shard.frameCache.get(packed);
            if (cached != null) {
                return RawReadResult.ofFrame(cached);
            }
        }
        if ((Object) shard.storage instanceof RawFrameReadAccess rawAccess) {
            byte[] diskFrame = rawAccess.chunking$readRawDeflateFrame(pos);
            if (diskFrame == RawFrameReadAccess.EMPTY_ABSENT_FRAME) {
                return RawReadResult.EMPTY;
            }
            if (diskFrame != null) {
                if (shard.frameCache != null) {
                    shard.frameCache.put(packed, diskFrame);
                }
                return RawReadResult.ofFrame(diskFrame);
            }
        }
        CompoundTag tag = shard.storage.read(pos);
        return tag != null ? RawReadResult.ofTag(tag) : RawReadResult.EMPTY;
    }

    public CompletableFuture<Void> scanChunk(
            ChunkPos pos,
            StreamTagVisitor visitor,
            AtomicBoolean shutdownRequested
    ) {
        Shard shard = shardFor(pos.getRegionX(), pos.getRegionZ());
        CompletableFuture<Void> future = new CompletableFuture<>();
        this.lifecycleLock.readLock().lock();
        try {
            if (this.closed || shutdownRequested.get()) {
                return CompletableFuture.completedFuture(null);
            }
            shard.executor.schedule(new StrictQueue.RunnableWithPriority(PRIO_FOREGROUND, () -> {
                if (shutdownRequested.get()) {
                    future.complete(null);
                    return;
                }
                try {
                    PendingWrite pending = shard.pendingWrites.get(pos);
                    if (pending != null) {
                        if (pending.data != null) {
                            EncodedCache.unwrapIfPreEncoded(pending.data).acceptAsRoot(visitor);
                        }
                        future.complete(null);
                        return;
                    }
                    shard.storage.scanChunk(pos, visitor);
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }));
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
        return future;
    }

    public CompletableFuture<Void> synchronize(boolean flush, AtomicBoolean shutdownRequested) {
        CompletableFuture<?>[] shardFutures = new CompletableFuture<?>[this.shards.length];
        this.lifecycleLock.readLock().lock();
        try {
            if (this.closed || shutdownRequested.get()) {
                return CompletableFuture.completedFuture(null);
            }
            for (int i = 0; i < this.shards.length; i++) {
                final Shard shard = this.shards[i];
                CompletableFuture<Void> shardSync = new CompletableFuture<>();
                shard.executor.schedule(new StrictQueue.RunnableWithPriority(PRIO_FOREGROUND, () -> {
                    while (!shard.pendingWrites.isEmpty()) {
                        storeOnePending(shard);
                    }
                    if (flush) {
                        try {
                            shard.storage.flush();
                        } catch (Throwable t) {
                            shardSync.completeExceptionally(t);
                            return;
                        }
                    }
                    shardSync.complete(null);
                }));
                shardFutures[i] = shardSync;
            }
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
        return CompletableFuture.allOf(shardFutures);
    }

    public boolean hasPreExistingRegionFile(int regionX, int regionZ) {
        Shard shard = shardFor(regionX, regionZ);
        return (Object) shard.storage instanceof RegionStorageAccess access
                && access.chunking$hasPreExistingRegionFile(regionX, regionZ);
    }

    public boolean hasRegionFileOnDisk(int regionX, int regionZ) {
        Shard shard = shardFor(regionX, regionZ);
        return (Object) shard.storage instanceof RegionStorageAccess access
                && access.chunking$hasRegionFileOnDisk(regionX, regionZ);
    }

    private static void tellStorePending(Shard shard, AtomicBoolean shutdownRequested) {
        if (!shard.drainingPending && shard.pendingWrites.size() > 32) {
            shard.drainingPending = true;
            try {
                int maxDrain = 16;
                while (maxDrain-- > 0 && shard.pendingWrites.size() > 8) {
                    storeOnePending(shard);
                }
            } finally {
                shard.drainingPending = false;
            }
        }
        if (!shard.storeScheduled && !shard.pendingWrites.isEmpty() && !shutdownRequested.get()) {
            shard.storeScheduled = true;
            shard.executor.schedule(new StrictQueue.RunnableWithPriority(PRIO_BACKGROUND, () -> {
                shard.storeScheduled = false;
                if (!shutdownRequested.get()) {
                    storeOnePending(shard);
                    if (!shard.pendingWrites.isEmpty()) {
                        tellStorePending(shard, shutdownRequested);
                    }
                }
            }));
        }
    }

    private static void storeOnePending(Shard shard) {
        Iterator<Map.Entry<ChunkPos, PendingWrite>> it = shard.pendingWrites.entrySet().iterator();
        if (!it.hasNext()) {
            return;
        }
        Map.Entry<ChunkPos, PendingWrite> entry = it.next();
        it.remove();
        runStore(shard, entry.getKey(), entry.getValue());
    }

    private static void runStore(Shard shard, ChunkPos pos, PendingWrite pw) {
        try {
            shard.storage.write(pos, pw.data);
            if (shard.frameCache != null) {
                long packed = pos.pack();
                if (pw.data == null) {
                    shard.frameCache.remove(packed);
                } else {
                    byte[] frame = EncodedCache.extractPreEncodedFrame(pw.data);
                    if (frame != null) {
                        shard.frameCache.put(packed, frame);
                    } else {
                        shard.frameCache.remove(packed);
                    }
                }
            }
            pw.result.complete(null);
        } catch (Throwable t) {
            LOGGER.error("Failed to store chunk {} on sharded I/O", pos, t);
            pw.result.completeExceptionally(t);
        }
    }

    public void close() throws IOException {
        CompletableFuture<?>[] barriers = new CompletableFuture<?>[this.shards.length];
        IOException[] flushErrors = new IOException[this.shards.length];
        this.lifecycleLock.writeLock().lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            for (int i = 0; i < this.shards.length; i++) {
                final int idx = i;
                final Shard shard = this.shards[i];
                CompletableFuture<Void> barrier = new CompletableFuture<>();
                shard.executor.schedule(new StrictQueue.RunnableWithPriority(PRIO_SHUTDOWN, () -> {
                    try {
                        while (!shard.pendingWrites.isEmpty()) {
                            storeOnePending(shard);
                        }
                        try {
                            shard.storage.flush();
                        } catch (IOException e) {
                            flushErrors[idx] = e;
                        }
                    } finally {
                        barrier.complete(null);
                    }
                }));
                barriers[i] = barrier;
            }
        } finally {
            this.lifecycleLock.writeLock().unlock();
        }
        CompletableFuture.allOf(barriers).join();

        IOException firstError = null;
        for (IOException fe : flushErrors) {
            if (fe != null) {
                if (firstError == null) {
                    firstError = fe;
                } else {
                    firstError.addSuppressed(fe);
                }
            }
        }
        for (Shard shard : this.shards) {
            try {
                shard.executor.close();
            } catch (Throwable ignored) {
            }
            try {
                shard.storage.close();
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                } else {
                    firstError.addSuppressed(e);
                }
            }
            if (shard.frameCache != null) {
                shard.frameCache.clear();
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }
}
