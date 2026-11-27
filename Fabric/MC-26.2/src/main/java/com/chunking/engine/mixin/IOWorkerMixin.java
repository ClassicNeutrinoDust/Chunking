package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.io.FrameCache;
import com.chunking.engine.io.EncodedCache;
import com.chunking.engine.io.ShardedIo;
import com.chunking.engine.access.PendingStoreAccess;
import com.chunking.engine.access.RawFrameReadAccess;
import com.chunking.engine.access.RegionStorageAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Dedicated IO worker and parallel zlib/NBT decode offload for IOWorker.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin implements RegionStorageAccess {
    @Unique
    private static final Logger CHUNKING$LOGGER = LoggerFactory.getLogger("Chunking");

    @Unique
    private static final AtomicInteger CHUNKING$IO_THREAD_IDS = new AtomicInteger(1);

    @Unique
    private static final AtomicInteger CHUNKING$IO_PARSE_THREAD_IDS = new AtomicInteger(1);

    @Unique
    private static volatile ExecutorService chunking$ioParseExecutor;

    @Unique
    private static final Object CHUNKING$IO_PARSE_LOCK = new Object();

    @Shadow
    @Final
    private AtomicBoolean shutdownRequested;

    @Shadow
    @Final
    private PriorityConsecutiveExecutor consecutiveExecutor;

    @Shadow
    @Final
    private RegionFileStorage storage;

    @Shadow
    @Final
    private java.util.SequencedMap<ChunkPos, ?> pendingWrites;

    @Shadow
    protected abstract boolean isOldChunk(CompoundTag tag);

    @Shadow
    public abstract CompletableFuture<Void> scanChunk(ChunkPos pos, StreamTagVisitor visitor);

    @Shadow
    private void storePendingChunk() {
        throw new AssertionError();
    }

    @Shadow
    private void tellStorePending() {
        throw new AssertionError();
    }

    @Unique
    private volatile ExecutorService chunking$dedicatedIoExecutor;

    @Unique
    private volatile FrameCache chunking$frameCache;

    @Unique
    private boolean chunking$frameCacheChecked;

    @Unique
    private boolean chunking$drainingPendingWrites;

    @Unique
    private volatile Set<Long> chunking$writtenRegions;

    @Unique
    private volatile ShardedIo chunking$shardedEngine;

    @Unique
    private Set<Long> chunking$ensureWrittenRegions() {
        Set<Long> set = this.chunking$writtenRegions;
        if (set == null) {
            synchronized (this) {
                set = this.chunking$writtenRegions;
                if (set == null) {
                    set = ConcurrentHashMap.newKeySet(64);
                    this.chunking$writtenRegions = set;
                }
            }
        }
        return set;
    }

    @Unique
    private static final CompletableFuture<Void> CHUNKING$NULL_VOID_FUTURE =
            CompletableFuture.completedFuture(null);

    @Unique
    private static final CompletableFuture<Optional<CompoundTag>> CHUNKING$EMPTY_LOAD_FUTURE =
            CompletableFuture.completedFuture(Optional.empty());

    @Unique
    private static ExecutorService chunking$ioParsePool() {
        ExecutorService exec = chunking$ioParseExecutor;
        if (exec == null) {
            synchronized (CHUNKING$IO_PARSE_LOCK) {
                exec = chunking$ioParseExecutor;
                if (exec == null) {
                    boolean isClient = ModConfig.isClientEnvironment();
                    int cpus = Runtime.getRuntime().availableProcessors();
                    int threads = isClient ? 2 : Math.max(2, Math.min(32, cpus / 4));
                    int prio = isClient
                            ? Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
                            : Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1);
                    exec = Executors.newFixedThreadPool(threads, r -> {
                        Thread t = new Thread(r, "Chunking-IOParse-" + CHUNKING$IO_PARSE_THREAD_IDS.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(prio);
                        return t;
                    });
                    chunking$ioParseExecutor = exec;
                }
            }
        }
        return exec;
    }

    @Unique
    private FrameCache chunking$getFrameCache() {
        if (!this.chunking$frameCacheChecked) {
            this.chunking$frameCacheChecked = true;
            if ("chunk".equals(this.storage.info().type())) {
                boolean isClient = ModConfig.isClientEnvironment();
                int cap = Integer.getInteger("chunking.io_frame_cache", isClient ? 32 : 2_048);
                if (cap > 0) {
                    this.chunking$frameCache = FrameCache.createUnsynchronized(cap);
                }
            }
        }
        return this.chunking$frameCache;
    }

    @Unique
    private ExecutorService chunking$getOrStartDedicatedIoExecutor() {
        ExecutorService exec = this.chunking$dedicatedIoExecutor;
        if (exec == null) {
            synchronized (this) {
                exec = this.chunking$dedicatedIoExecutor;
                if (exec == null) {
                    int prio = ModConfig.isClientEnvironment()
                            ? Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
                            : Thread.NORM_PRIORITY;
                    exec = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "Chunking-IOWorker-" + CHUNKING$IO_THREAD_IDS.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(prio);
                        return t;
                    });
                    this.chunking$dedicatedIoExecutor = exec;
                }
            }
        }
        return exec;
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/thread/PriorityConsecutiveExecutor;<init>(ILjava/util/concurrent/Executor;Ljava/lang/String;)V"
            ),
            index = 1
    )
    private Executor chunking$useDedicatedIoExecutor(Executor original) {
        return command -> this.chunking$getOrStartDedicatedIoExecutor().execute(command);
    }

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)V",
            at = @At("RETURN")
    )
    private void chunking$initShardedEngine(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
        if (Runtime.getRuntime().availableProcessors() >= 8
                && "chunk".equals(info.type())) {
            this.chunking$shardedEngine = new ShardedIo(info, folder, sync, this.storage);
        }
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void chunking$closeShardedEngine(CallbackInfo ci) throws IOException {
        ShardedIo sharded = this.chunking$shardedEngine;
        if (sharded == null) {
            return;
        }
        if (this.shutdownRequested.compareAndSet(false, true)) {
            try {
                sharded.close();
            } finally {
                try {
                    this.consecutiveExecutor.close();
                } catch (Throwable ignored) {
                }
                ExecutorService dedicated = this.chunking$dedicatedIoExecutor;
                if (dedicated != null) {
                    dedicated.shutdown();
                }
                FrameCache fc = this.chunking$frameCache;
                if (fc != null) {
                    fc.clear();
                }
            }
        }
        ci.cancel();
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void chunking$shutdownDedicatedIoExecutor(CallbackInfo ci) {
        ExecutorService dedicated = this.chunking$dedicatedIoExecutor;
        if (dedicated != null) {
            dedicated.shutdown();
        }
        FrameCache fc = this.chunking$frameCache;
        if (fc != null) {
            fc.clear();
        }
    }

    @Inject(method = "tellStorePending", at = @At("HEAD"))
    private void chunking$drainPendingWritesWhenBackedUp(CallbackInfo ci) {
        if (this.shutdownRequested.get() || this.chunking$drainingPendingWrites || this.pendingWrites.size() <= 32) {
            return;
        }
        this.chunking$drainingPendingWrites = true;
        try {
            int maxDrain = 16;
            while (maxDrain-- > 0 && this.pendingWrites.size() > 8) {
                this.storePendingChunk();
            }
        } finally {
            this.chunking$drainingPendingWrites = false;
        }
    }

    @WrapOperation(
            method = "runStore",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"
            )
    )
    private void chunking$cacheFrameOnStore(
            RegionFileStorage storageInstance,
            ChunkPos pos,
            CompoundTag nbt,
            Operation<Void> original
    ) {
        original.call(storageInstance, pos, nbt);
        FrameCache fc = chunking$getFrameCache();
        if (fc != null) {
            long packed = pos.pack();
            if (nbt == null) {
                fc.remove(packed);
            } else {
                byte[] frame = EncodedCache.extractPreEncodedFrame(nbt);
                if (frame != null) {
                    fc.put(packed, frame);
                } else {
                    fc.remove(packed);
                }
            }
        }
    }

    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunking$recordWrittenRegionOnStore(
            ChunkPos pos,
            Supplier<CompoundTag> dataSupplier,
            CallbackInfoReturnable<CompletableFuture<Void>> cir
    ) {
        if (pos != null) {
            this.chunking$ensureWrittenRegions().add(Long.valueOf(ChunkPos.pack(pos.getRegionX(), pos.getRegionZ())));
            ShardedIo sharded = this.chunking$shardedEngine;
            if (sharded != null) {
                cir.setReturnValue(sharded.store(pos, dataSupplier, this.shutdownRequested));
            }
        }
    }

    @Inject(method = "synchronize", at = @At("HEAD"), cancellable = true)
    private void chunking$synchronizeSharded(boolean flush, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ShardedIo sharded = this.chunking$shardedEngine;
        if (sharded != null) {
            cir.setReturnValue(sharded.synchronize(flush, this.shutdownRequested));
        }
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void chunking$skipScanIfNoRegionFile(
            ChunkPos pos,
            StreamTagVisitor visitor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir
    ) {
        if (pos != null && !this.chunking$hasRegionFileOnDisk(pos.getRegionX(), pos.getRegionZ())) {
            cir.setReturnValue(CHUNKING$NULL_VOID_FUTURE);
            return;
        }
        ShardedIo sharded = this.chunking$shardedEngine;
        if (sharded != null && pos != null) {
            cir.setReturnValue(sharded.scanChunk(pos, visitor, this.shutdownRequested));
        }
    }

    @Unique
    private static CompletableFuture<Optional<CompoundTag>> chunking$decodeRawFrameAsync(byte[] frame) {
        return CompletableFuture.supplyAsync(() -> {
            CompoundTag decoded = EncodedCache.decodeDeflateFrame(frame);
            if (decoded != null) {
                return Optional.of(decoded);
            }
            if (frame.length > 5 && frame[4] == (byte) RegionFileVersion.VERSION_DEFLATE.getId()) {
                try (DataInputStream dis = new DataInputStream(
                        RegionFileVersion.VERSION_DEFLATE.wrap(
                                new ByteArrayInputStream(frame, 5, frame.length - 5)))) {
                    return Optional.ofNullable(NbtIo.read(dis));
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }
            throw new java.util.concurrent.CompletionException(
                    new IOException("Corrupt or unsupported chunk frame (len=" + frame.length + ")"));
        }, chunking$ioParsePool());
    }

    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void chunking$parallelReadDecompress(
            ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir
    ) {
        if (pos != null && !this.chunking$hasRegionFileOnDisk(pos.getRegionX(), pos.getRegionZ())) {
            cir.setReturnValue(CHUNKING$EMPTY_LOAD_FUTURE);
            return;
        }
        // Always route reads through the sharded engine when active, regardless of
        // PARALLEL_STATUS_TASKS, so every .mca file remains exclusively owned by its shard.
        ShardedIo sharded = this.chunking$shardedEngine;
        if (sharded != null && pos != null) {
            cir.setReturnValue(sharded.loadRawAsync(pos, this.shutdownRequested).thenCompose(this::chunking$decodeRawResult));
            return;
        }
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return;
        }
        CompletableFuture<ShardedIo.RawReadResult> ioFuture = this.consecutiveExecutor.scheduleWithResult(0, future -> {
            if (!this.shutdownRequested.get()) {
                try {
                    future.complete(chunking$readRawOnIoThread(pos));
                } catch (Throwable t) {
                    CHUNKING$LOGGER.warn("Failed to read chunk {}", pos, t);
                    future.completeExceptionally(t);
                }
            } else {
                future.complete(ShardedIo.RawReadResult.EMPTY);
            }
            this.tellStorePending();
        });
        cir.setReturnValue(ioFuture.thenCompose(this::chunking$decodeRawResult));
    }

    @Unique
    private CompletableFuture<Optional<CompoundTag>> chunking$decodeRawResult(ShardedIo.RawReadResult raw) {
        if (raw == null || raw.isEmpty()) {
            return CHUNKING$EMPTY_LOAD_FUTURE;
        }
        if (raw.tag != null) {
            return CompletableFuture.completedFuture(
                    Optional.of(EncodedCache.unwrapIfPreEncoded(raw.tag)));
        }
        return chunking$decodeRawFrameAsync(raw.frame);
    }

    @Unique
    private ShardedIo.RawReadResult chunking$readRawOnIoThread(ChunkPos pos) throws java.io.IOException {
        Object pending = this.pendingWrites.get(pos);
        if (pending != null) {
            CompoundTag rawTag = (pending instanceof PendingStoreAccess psa) ? psa.chunking$rawData() : null;
            if (rawTag == null) {
                return ShardedIo.RawReadResult.EMPTY;
            }
            byte[] preEncoded = EncodedCache.extractPreEncodedFrame(rawTag);
            if (preEncoded != null) {
                return ShardedIo.RawReadResult.ofFrame(preEncoded);
            }
            return ShardedIo.RawReadResult.ofTag(rawTag.copy());
        }
        FrameCache fc = chunking$getFrameCache();
        long packed = pos.pack();
        if (fc != null) {
            byte[] cached = fc.get(packed);
            if (cached != null) {
                return ShardedIo.RawReadResult.ofFrame(cached);
            }
        }
        if ((Object) this.storage instanceof RawFrameReadAccess rawAccess) {
            byte[] diskFrame = rawAccess.chunking$readRawDeflateFrame(pos);
            if (diskFrame == RawFrameReadAccess.EMPTY_ABSENT_FRAME) {
                return ShardedIo.RawReadResult.EMPTY;
            }
            if (diskFrame != null) {
                if (fc != null) {
                    fc.put(packed, diskFrame);
                }
                return ShardedIo.RawReadResult.ofFrame(diskFrame);
            }
        }
        CompoundTag tag = this.storage.read(pos);
        return tag != null
                ? ShardedIo.RawReadResult.ofTag(tag)
                : ShardedIo.RawReadResult.EMPTY;
    }

    @Inject(method = "isOldChunkAround", at = @At("HEAD"), cancellable = true)
    private void chunking$fastIsOldChunkAround(ChunkPos pos, int range, CallbackInfoReturnable<Boolean> cir) {
        if (this.chunking$shardedEngine != null || (Object) this.storage instanceof RegionStorageAccess) {
            int minRx = (pos.x() - range) >> 5;
            int maxRx = (pos.x() + range) >> 5;
            int minRz = (pos.z() - range) >> 5;
            int maxRz = (pos.z() + range) >> 5;
            for (int rx = minRx; rx <= maxRx; rx++) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    if (this.chunking$hasPreExistingRegionFile(rx, rz)) {
                        return;
                    }
                }
            }
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    @WrapOperation(
            method = "lambda$loadAsync$0",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Optional;ofNullable(Ljava/lang/Object;)Ljava/util/Optional;"
            ),
            require = 0
    )
    private java.util.Optional<?> chunking$unwrapPreEncodedOnLoad(
            Object value,
            Operation<java.util.Optional<?>> original
    ) {
        if (value instanceof CompoundTag tag) {
            value = EncodedCache.unwrapIfPreEncoded(tag);
        }
        return original.call(value);
    }

    @WrapOperation(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/CompoundTag;acceptAsRoot(Lnet/minecraft/nbt/StreamTagVisitor;)V"
            ),
            require = 0
    )
    private void chunking$unwrapPreEncodedOnScan(
            CompoundTag instance,
            StreamTagVisitor visitor,
            Operation<Void> original
    ) {
        original.call(EncodedCache.unwrapIfPreEncoded(instance), visitor);
    }

    @Inject(method = "createOldDataForRegion", at = @At("HEAD"), cancellable = true)
    private void chunking$fastCreateOldDataForRegion(
            int regionX,
            int regionZ,
            CallbackInfoReturnable<CompletableFuture<BitSet>> cir
    ) {
        if ((this.chunking$shardedEngine != null || (Object) this.storage instanceof RegionStorageAccess)
                && !this.chunking$hasPreExistingRegionFile(regionX, regionZ)) {
            cir.setReturnValue(CompletableFuture.completedFuture(new BitSet()));
            return;
        }

        BitSet bitSet = new BitSet(1024);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[1024];
        int minCx = regionX << 5;
        int minCz = regionZ << 5;
        int idx = 0;
        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                ChunkPos pos = new ChunkPos(minCx + dx, minCz + dz);
                CollectFields collector = new CollectFields(
                        new FieldSelector(IntTag.TYPE, "DataVersion"),
                        new FieldSelector(CompoundTag.TYPE, "blending_data")
                );
                final int bitIndex = dz * 32 + dx;
                futures[idx++] = this.scanChunk(pos, collector)
                        .thenRun(() -> {
                            if (collector.getResult() instanceof CompoundTag tag && this.isOldChunk(tag)) {
                                synchronized (bitSet) {
                                    bitSet.set(bitIndex);
                                }
                            }
                        })
                        .exceptionally(err -> null);
            }
        }
        cir.setReturnValue(CompletableFuture.allOf(futures).thenApply(ignored -> bitSet));
    }

    @Override
    public boolean chunking$hasPreExistingRegionFile(int regionX, int regionZ) {
        ShardedIo sharded = this.chunking$shardedEngine;
        if (sharded != null) {
            return sharded.hasPreExistingRegionFile(regionX, regionZ);
        }
        return (Object) this.storage instanceof RegionStorageAccess access
                && access.chunking$hasPreExistingRegionFile(regionX, regionZ);
    }

    @Override
    public boolean chunking$hasRegionFileOnDisk(int regionX, int regionZ) {
        Set<Long> written = this.chunking$writtenRegions;
        if (written != null && written.contains(Long.valueOf(ChunkPos.pack(regionX, regionZ)))) {
            return true;
        }
        ShardedIo sharded = this.chunking$shardedEngine;
        if (sharded != null) {
            return sharded.hasRegionFileOnDisk(regionX, regionZ);
        }
        return (Object) this.storage instanceof RegionStorageAccess access
                && access.chunking$hasRegionFileOnDisk(regionX, regionZ);
    }
}
