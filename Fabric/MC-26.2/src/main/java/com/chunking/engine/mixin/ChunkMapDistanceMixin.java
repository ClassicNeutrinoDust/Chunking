package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.io.EncodedCache;
import com.chunking.engine.io.RecentChunks;
import com.chunking.engine.io.RegionMask;
import com.chunking.engine.io.RegionKinds;
import com.chunking.engine.io.UnloadSaveCtx;
import com.chunking.engine.mixin.accessor.SimpleRegionStorageAccessor;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.TaskQueue;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.scheduler.MoveTracker;
import com.chunking.engine.world.HolderTable;
import com.chunking.engine.access.RegionStorageAccess;
import com.chunking.engine.access.SectionStorageEvictAccess;
import com.chunking.engine.world.TrackedHolders;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * ChunkMap fast paths: dedicated light thread, cached region chunk-type and recent-data
 * lookups for unload/load, and bounded unload budgets when players are online.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDistanceMixin {
    @Unique
    private static final AtomicInteger CHUNKING$LIGHT_THREAD_IDS = new AtomicInteger(1);

    @Unique
    private static final AtomicInteger CHUNKING$SAVE_SERIALIZER_IDS = new AtomicInteger(1);

    @Unique
    private static final ThreadLocal<Long> CHUNKING$SAVING_CHUNK_POS = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<SerializableChunkData> CHUNKING$SAVING_CHUNK_DATA = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<CompletableFuture<CompoundTag>> CHUNKING$SAVE_TAG_FUTURE = new ThreadLocal<>();

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Shadow
    protected abstract ChunkAccess handleChunkLoadFailure(Throwable throwable, ChunkPos pos);

    @Shadow
    protected abstract ChunkAccess createEmptyChunk(ChunkPos pos);

    @Shadow
    protected abstract byte markPosition(ChunkPos pos, ChunkType chunkType);

    @Shadow
    protected abstract void markPositionReplaceable(ChunkPos pos);

    @Shadow
    @Final
    private Queue<Runnable> unloadQueue;

    @Shadow
    @Final
    private it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Shadow
    @Final
    private it.unimi.dsi.fastutil.longs.LongSet toDrop;

    @Shadow
    @Final
    private AtomicInteger activeChunkWrites;

    @Unique
    private static final int CHUNKING$TYPE_RING_CAP = 8_192;

    @Shadow
    @Final
    @Mutable
    private it.unimi.dsi.fastutil.longs.Long2ByteMap chunkTypeCache;

    @Shadow
    @Final
    @Mutable
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Shadow
    private boolean modified;

    @Shadow
    @Final
    private it.unimi.dsi.fastutil.longs.LongSet chunksToEagerlySave;

    @Shadow
    protected abstract void saveChunksEagerly(BooleanSupplier hasTimeLeft);

    @Unique
    private TrackedHolders<ChunkHolder> chunking$trackedUpdatingMap;

    @Unique
    private HolderTable<ChunkHolder> chunking$visibleConcurrent;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$synchronizeChunkTypeCache(CallbackInfo ci) {
        this.chunkTypeCache = it.unimi.dsi.fastutil.longs.Long2ByteMaps.synchronize(this.chunkTypeCache);
        TrackedHolders<ChunkHolder> tracked = new TrackedHolders<>(256);
        if (this.updatingChunkMap != null && !this.updatingChunkMap.isEmpty()) {
            tracked.putAll(this.updatingChunkMap);
        }
        this.updatingChunkMap = tracked;
        this.chunking$trackedUpdatingMap = tracked;
        Long2ObjectLinkedOpenHashMap<ChunkHolder> vis = new Long2ObjectLinkedOpenHashMap<>(256);
        HolderTable<ChunkHolder> conc = new HolderTable<>(256);
        if (!tracked.isEmpty()) {
            tracked.flushTo(vis, conc);
        }
        this.visibleChunkMap = vis;
        this.chunking$visibleConcurrent = conc;
    }

    @Inject(method = "getVisibleChunkIfPresent", at = @At("HEAD"), cancellable = true)
    private void chunking$fastGetVisibleChunkIfPresent(long pos, CallbackInfoReturnable<ChunkHolder> cir) {
        HolderTable<ChunkHolder> conc = this.chunking$visibleConcurrent;
        if (conc != null) {
            cir.setReturnValue(conc.get(pos));
        }
    }

    @Inject(method = "size", at = @At("HEAD"), cancellable = true)
    private void chunking$fastSize(CallbackInfoReturnable<Integer> cir) {
        HolderTable<ChunkHolder> conc = this.chunking$visibleConcurrent;
        if (conc != null) {
            cir.setReturnValue(Integer.valueOf(conc.size()));
        }
    }

    @Inject(method = "promoteChunkMap", at = @At("HEAD"), cancellable = true)
    private void chunking$deltaPromoteChunkMap(CallbackInfoReturnable<Boolean> cir) {
        if (!this.modified) {
            cir.setReturnValue(Boolean.FALSE);
            return;
        }
        TrackedHolders<ChunkHolder> tracked = this.chunking$trackedUpdatingMap;
        HolderTable<ChunkHolder> conc = this.chunking$visibleConcurrent;
        if (tracked != null && conc != null) {
            tracked.flushTo(this.visibleChunkMap, conc);
            this.modified = false;
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    @Unique
    private ExecutorService chunking$dedicatedLightExecutor;

    @Unique
    private volatile ExecutorService chunking$saveSerializerExecutor;

    @Unique
    private volatile ConcurrentHashMap<Long, CompletableFuture<Void>> chunking$inFlightSaves;

    @Unique
    private volatile RecentChunks chunking$recentSavedData;

    @Unique
    private volatile RegionMask chunking$savedChunks;

    @Unique
    private volatile RegionKinds chunking$chunkTypeTable;

    @Unique
    private long[] chunking$typeRingKeys;

    @Unique
    private int chunking$typeRingHead;

    @Unique
    private boolean chunking$typeRingFull;

    @Unique
    private ConcurrentHashMap<Long, CompletableFuture<Void>> chunking$inFlightSavesMap() {
        ConcurrentHashMap<Long, CompletableFuture<Void>> m = this.chunking$inFlightSaves;
        if (m == null) {
            synchronized (this) {
                m = this.chunking$inFlightSaves;
                if (m == null) {
                    m = new ConcurrentHashMap<>(256);
                    this.chunking$inFlightSaves = m;
                }
            }
        }
        return m;
    }

    @Unique
    private RecentChunks chunking$recentCache() {
        RecentChunks c = this.chunking$recentSavedData;
        if (c == null) {
            synchronized (this) {
                c = this.chunking$recentSavedData;
                if (c == null) {
                    boolean isClient = ModConfig.isClientEnvironment();
                    c = new RecentChunks(
                            Integer.getInteger("chunking.recent_light_proto_cache", isClient ? 32 : 4_096),
                            Integer.getInteger("chunking.recent_proto_cache", isClient ? 16 : 768),
                            Integer.getInteger("chunking.recent_full_cache", isClient ? 0 : 256)
                    );
                    this.chunking$recentSavedData = c;
                }
            }
        }
        return c;
    }

    @Unique
    private RegionMask chunking$savedChunksSet() {
        RegionMask s = this.chunking$savedChunks;
        if (s == null) {
            synchronized (this) {
                s = this.chunking$savedChunks;
                if (s == null) {
                    s = new RegionMask(64);
                    this.chunking$savedChunks = s;
                }
            }
        }
        return s;
    }

    @Unique
    private RegionKinds chunking$typeTable() {
        RegionKinds t = this.chunking$chunkTypeTable;
        if (t == null) {
            synchronized (this) {
                t = this.chunking$chunkTypeTable;
                if (t == null) {
                    t = new RegionKinds(64);
                    this.chunking$chunkTypeTable = t;
                }
            }
        }
        return t;
    }

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Ljava/util/concurrent/Executor;Ljava/lang/String;)Lnet/minecraft/util/thread/ConsecutiveExecutor;"
            )
    )
    private ConsecutiveExecutor chunking$useDedicatedLightThread(
            Executor executor,
            String name,
            Operation<ConsecutiveExecutor> original
    ) {
        if ("light".equals(name)) {
            boolean isClient = ModConfig.isClientEnvironment();
            int prio = isClient
                    ? Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1)
                    : Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1);
            this.chunking$dedicatedLightExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    if (!isClient) {
                        com.chunking.engine.core.ThreadHints.preferHighPerformanceCurrentThread();
                    }
                    r.run();
                }, "Chunking-Light-" + CHUNKING$LIGHT_THREAD_IDS.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(prio);
                return t;
            });
            return original.call(this.chunking$dedicatedLightExecutor, name);
        }
        return original.call(executor, name);
    }

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/util/thread/TaskScheduler;Ljava/util/concurrent/Executor;)Lnet/minecraft/server/level/ChunkTaskDispatcher;"
            )
    )
    private ChunkTaskDispatcher chunking$useDedicatedLightDispatcherExecutor(
            TaskScheduler<Runnable> scheduler,
            Executor executor,
            Operation<ChunkTaskDispatcher> original
    ) {
        if ("light".equals(scheduler.name()) && this.chunking$dedicatedLightExecutor != null) {
            return original.call(scheduler, this.chunking$dedicatedLightExecutor);
        }
        return original.call(scheduler, executor);
    }

    @Unique
    private boolean chunking$inSaveAllFlush;

    @Unique
    private void chunking$drainInFlightSaves(long timeoutSeconds) {
        ConcurrentHashMap<Long, CompletableFuture<Void>> inFlight = this.chunking$inFlightSaves;
        if (inFlight == null || inFlight.isEmpty()) {
            return;
        }
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!inFlight.isEmpty()) {
            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0L) {
                break;
            }
            CompletableFuture<?>[] pending = inFlight.values().toArray(CompletableFuture[]::new);
            if (pending.length == 0) {
                break;
            }
            try {
                CompletableFuture.allOf(pending).get(remainingNs, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    @WrapMethod(method = "saveAllChunks")
    private void chunking$flushInFlightSavesOnSaveAll(boolean flush, Operation<Void> original) {
        if (!flush) {
            original.call(false);
            return;
        }
        this.chunking$inSaveAllFlush = true;
        try {
            original.call(true);
        } finally {
            this.chunking$inSaveAllFlush = false;
        }
    }

    @Inject(
            method = "saveAllChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;synchronize(Z)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private void chunking$drainPendingSavesBeforeSynchronize(boolean flush, CallbackInfo ci) {
        if (flush) {
            chunking$drainInFlightSaves(30L);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void chunking$drainInFlightSavesBeforeClose(CallbackInfo ci) {
        chunking$drainInFlightSaves(30L);
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void chunking$shutdownDedicatedLightThread(CallbackInfo ci) {
        if (this.chunking$dedicatedLightExecutor != null) {
            this.chunking$dedicatedLightExecutor.shutdown();
            try {
                this.chunking$dedicatedLightExecutor.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.chunking$saveSerializerExecutor != null) {
            this.chunking$saveSerializerExecutor.shutdown();
            try {
                this.chunking$saveSerializerExecutor.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        RecentChunks c = this.chunking$recentSavedData;
        if (c != null) {
            c.clear();
        }
        RegionMask s = this.chunking$savedChunks;
        if (s != null) {
            s.clear();
        }
        RegionKinds t = this.chunking$chunkTypeTable;
        if (t != null) {
            t.clear();
        }
        this.chunking$typeRingKeys = null;
        this.chunking$typeRingHead = 0;
        this.chunking$typeRingFull = false;
    }

    @Inject(method = "markPosition", at = @At("HEAD"))
    private void chunking$recordPositionType(ChunkPos pos, ChunkType chunkType, CallbackInfoReturnable<Byte> cir) {
        if (chunkType == ChunkType.LEVELCHUNK) {
            this.chunking$typeTable().setFull(pos.pack());
        } else {
            this.chunking$typeTable().setProto(pos.pack());
        }
    }

    @Inject(method = "markPositionReplaceable", at = @At("HEAD"))
    private void chunking$recordPositionReplaceable(ChunkPos pos, CallbackInfo ci) {
        this.chunking$typeTable().setProto(pos.pack());
    }

    @WrapOperation(
            method = "lambda$scheduleUnload$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z"
            ),
            require = 0
    )
    private boolean chunking$zeroCopySectionsDuringUnloadSave(
            ChunkMap instance,
            ChunkAccess chunk,
            Operation<Boolean> original
    ) {
        UnloadSaveCtx.enter();
        try {
            return original.call(instance, chunk);
        } finally {
            UnloadSaveCtx.exit();
        }
    }

    @WrapOperation(
            method = "lambda$scheduleUnload$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2LongMap;remove(J)J"
            ),
            require = 0
    )
    private long chunking$evictCachesOnChunkUnload(
            it.unimi.dsi.fastutil.longs.Long2LongMap instance,
            long posLong,
            Operation<Long> original
    ) {
        long res = original.call(instance, posLong);
        this.chunksToEagerlySave.remove(posLong);
        long[] ring = this.chunking$typeRingKeys;
        if (ring == null) {
            ring = new long[CHUNKING$TYPE_RING_CAP];
            this.chunking$typeRingKeys = ring;
        }
        int idx = this.chunking$typeRingHead;
        boolean wasFull = this.chunking$typeRingFull;
        long expired = ring[idx];
        ring[idx] = posLong;
        idx++;
        if (idx >= CHUNKING$TYPE_RING_CAP) {
            idx = 0;
            this.chunking$typeRingFull = true;
        }
        this.chunking$typeRingHead = idx;
        if (wasFull && expired != posLong) {
            this.chunkTypeCache.remove(expired);
        }
        if ((Object) this.poiManager instanceof SectionStorageEvictAccess evict) {
            evict.chunking$evictUnloadedChunk(posLong);
        }
        return res;
    }

    @Redirect(
            method = "getChunkToSend(J)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkHolder;getChunkToSend()Lnet/minecraft/world/level/chunk/LevelChunk;"
            ),
            require = 0
    )
    private LevelChunk chunking$getFullChunkToSendImmediately(ChunkHolder chunkHolder) {
        if (!chunkHolder.getSendSyncFuture().isDone()) {
            return null;
        }
        return chunkHolder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    @ModifyConstant(method = "setServerViewDistance", constant = @Constant(intValue = 32))
    private int chunking$unlockMaxServerViewDistance(int vanillaMax) {
        return Math.max(vanillaMax, ModConfig.FULL_CHUNK_RADIUS);
    }

    @ModifyArg(
            method = "setServerViewDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;updatePlayerTickets(I)V"
            ),
            index = 0
    )
    private int chunking$boundFullChunkTicketRadius(int viewDistance) {
        return ModConfig.effectiveFullChunkRadius(viewDistance);
    }

    /**
     * Bounds {@code processUnloads} tightly during live play (integrated client or dedicated server
     * with players online under MSPT pressure) while preserving full 8-20 ms throughput when unloads
     * back up during headless Chunky pregeneration sweeps (no players online).
     */
    @ModifyVariable(method = "processUnloads", at = @At("HEAD"), argsOnly = true)
    private BooleanSupplier chunking$budgetProcessUnloadsTime(BooleanSupplier hasTimeLeft) {
        if (this.chunking$inSaveAllFlush) {
            return hasTimeLeft;
        }
        int pending = this.pendingUnloads.size() + this.toDrop.size();
        long budgetNs;
        FrameGuard gov = FrameGuard.get();
        if (gov.hasActiveClientFrames()) {
            if (pending > 2048) {
                budgetNs = 8_000_000L;
            } else if (pending > 512) {
                budgetNs = gov.isServerTickPressured() ? 3_000_000L : 4_000_000L;
            } else if (pending > 192) {
                budgetNs = gov.isServerTickPressured() ? 1_250_000L : 1_750_000L;
            } else if (gov.isServerTickPressured()) {
                budgetNs = 500_000L;
            } else {
                budgetNs = pending > 64 ? 1_000_000L : 750_000L;
            }
        } else if (!this.level.players().isEmpty()) {
            double lastMs = TickBudget.lastRecordedTickMs();
            if (gov.level() == FrameGuard.Level.SEVERE || lastMs > 35.0D) {
                budgetNs = pending > 3072 ? 3_000_000L : 1_500_000L;
            } else if (gov.isServerTickPressured() || lastMs > 25.0D) {
                budgetNs = pending > 3072 ? 5_000_000L
                        : (pending > 1024 ? 3_000_000L : 1_500_000L);
            } else {
                budgetNs = pending > 3072 ? 10_000_000L
                        : (pending > 1536 ? 6_000_000L
                        : (pending > 384 ? 4_000_000L
                        : (pending > 96 ? 2_500_000L : 1_500_000L)));
            }
        } else {
            budgetNs = pending > 3072 ? 20_000_000L
                    : (pending > 1536 ? 14_000_000L
                    : (pending > 384 ? 8_000_000L
                    : (pending > 96 ? 3_500_000L : 1_500_000L)));
        }
        final long deadlineNs = System.nanoTime() + budgetNs;
        return () -> hasTimeLeft.getAsBoolean() && System.nanoTime() < deadlineNs;
    }

    @WrapMethod(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z")
    private boolean chunking$wrapSaveWithCleanup(ChunkAccess chunk, Operation<Boolean> original) {
        CHUNKING$SAVING_CHUNK_POS.set(Long.valueOf(chunk.getPos().pack()));
        CHUNKING$SAVING_CHUNK_DATA.remove();
        CHUNKING$SAVE_TAG_FUTURE.remove();
        try {
            return original.call(chunk);
        } finally {
            CHUNKING$SAVING_CHUNK_POS.remove();
            CHUNKING$SAVING_CHUNK_DATA.remove();
            CHUNKING$SAVE_TAG_FUTURE.remove();
        }
    }

    @WrapOperation(
            method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"
            ),
            require = 0
    )
    private SerializableChunkData chunking$captureSerializableChunkData(
            ServerLevel lvl,
            ChunkAccess chunk,
            Operation<SerializableChunkData> original
    ) {
        SerializableChunkData data = original.call(lvl, chunk);
        long packed = chunk.getPos().pack();
        this.chunking$savedChunksSet().add(packed);
        if (chunk.getPersistedStatus().getChunkType() == ChunkType.LEVELCHUNK) {
            this.chunking$typeTable().setFull(packed);
        } else {
            this.chunking$typeTable().setProto(packed);
        }
        CHUNKING$SAVING_CHUNK_DATA.set(data);
        return data;
    }

    @Redirect(
            method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            ),
            require = 0
    )
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> chunking$boundAndDeferSaveSerialization(
            Supplier<T> supplier,
            Executor originalExecutor
    ) {
        Long posObj = CHUNKING$SAVING_CHUNK_POS.get();
        final long packedPos = posObj != null ? posObj.longValue() : Long.MIN_VALUE;
        if (posObj != null) {
            this.chunking$savedChunksSet().add(packedPos);
        }
        ExecutorService exec = this.chunking$saveSerializerExecutor;
        if (exec == null) {
            synchronized (this) {
                exec = this.chunking$saveSerializerExecutor;
                if (exec == null) {
                    boolean isClient = ModConfig.isClientEnvironment()
                            || FrameGuard.get().hasActiveClientFrames();
                    int cpus = Runtime.getRuntime().availableProcessors();
                    int workers = Math.max(1, ModConfig.WORLDGEN_WORKERS);
                    int threads = isClient
                            ? Math.max(2, Math.min(4, cpus / 4))
                            : Math.max(4, Math.min(16, Math.max(workers / 2, cpus / 3)));
                    int queueCap = isClient ? 64 : 256;
                    int prio = isClient
                            ? Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
                            : Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1);
                    exec = new ThreadPoolExecutor(
                            threads,
                            threads,
                            0L,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(queueCap),
                            r -> {
                                Thread t = new Thread(r, "Chunking-SaveSer-" + CHUNKING$SAVE_SERIALIZER_IDS.getAndIncrement());
                                t.setDaemon(true);
                                t.setPriority(prio);
                                return t;
                            },
                            new ThreadPoolExecutor.CallerRunsPolicy()
                    );
                    this.chunking$saveSerializerExecutor = exec;
                }
            }
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            T result = supplier.get();
            if (result instanceof CompoundTag tag && packedPos != Long.MIN_VALUE) {
                try {
                    byte[] frame = EncodedCache.encodeDeflateFrame(tag);
                    if (frame != null) {
                        return (T) EncodedCache.wrapPreEncodedFrame(frame);
                    }
                } catch (Throwable ignored) {
                }
            }
            return result;
        }, exec);
        CompletableFuture<CompoundTag> tagFuture = (CompletableFuture<CompoundTag>) future;
        CHUNKING$SAVE_TAG_FUTURE.set(tagFuture);
        SerializableChunkData data = CHUNKING$SAVING_CHUNK_DATA.get();
        if (data != null && packedPos != Long.MIN_VALUE) {
            CompletableFuture<Void> doneSignal = tagFuture.thenRun(() -> {});
            this.chunking$recentCache().put(packedPos, data, doneSignal);
        }
        return future;
    }

    @WrapOperation(
            method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"
            ),
            require = 0
    )
    private CompletableFuture<Void> chunking$nonBlockingWriteAfterSerialize(
            ChunkMap instance,
            ChunkPos pos,
            Supplier<CompoundTag> originalSupplier,
            Operation<CompletableFuture<Void>> original
    ) {
        CompletableFuture<CompoundTag> tagFuture = CHUNKING$SAVE_TAG_FUTURE.get();
        if (tagFuture != null) {
            CHUNKING$SAVE_TAG_FUTURE.remove();
            Long key = Long.valueOf(pos.pack());
            ConcurrentHashMap<Long, CompletableFuture<Void>> inFlight = this.chunking$inFlightSavesMap();
            CompletableFuture<Void> enqueuedSignal = new CompletableFuture<>();
            CompletableFuture<Void> prev = inFlight.put(key, enqueuedSignal);
            CompletableFuture<CompoundTag> orderedTag = (prev == null)
                    ? tagFuture
                    : prev.handle((v, e) -> null).thenCombine(tagFuture, (ignored, tag) -> tag);
            CompletableFuture<Void> writeFuture = orderedTag.thenCompose(tag -> {
                try {
                    return original.call(instance, pos, (Supplier<CompoundTag>) () -> tag);
                } finally {
                    enqueuedSignal.complete(null);
                    inFlight.remove(key, enqueuedSignal);
                }
            });
            writeFuture.whenComplete((v, err) -> {
                if (!enqueuedSignal.isDone()) {
                    enqueuedSignal.complete(null);
                    inFlight.remove(key, enqueuedSignal);
                }
            });
            return writeFuture;
        }
        return original.call(instance, pos, originalSupplier);
    }

    @Inject(method = "isExistingChunkFull", at = @At("HEAD"), cancellable = true)
    private void chunking$fastIsExistingChunkFull(ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        long packed = pos.pack();
        int tableState = this.chunking$typeTable().get(packed);
        if (tableState == RegionKinds.STATE_FULL) {
            cir.setReturnValue(Boolean.TRUE);
            return;
        }
        if (tableState == RegionKinds.STATE_PROTO) {
            cir.setReturnValue(Boolean.FALSE);
            return;
        }
        byte cachedType = this.chunkTypeCache.get(packed);
        if (cachedType != 0) {
            boolean isFull = cachedType == 1;
            if (isFull) {
                this.chunking$typeTable().setFull(packed);
            } else {
                this.chunking$typeTable().setProto(packed);
            }
            cir.setReturnValue(Boolean.valueOf(isFull));
            return;
        }
        RecentChunks recent = this.chunking$recentSavedData;
        if (recent != null) {
            int typeFlag = recent.peekChunkType(packed);
            if (typeFlag == 1) {
                this.chunking$typeTable().setFull(packed);
                this.markPosition(pos, ChunkType.LEVELCHUNK);
                cir.setReturnValue(Boolean.TRUE);
                return;
            } else if (typeFlag == -1) {
                this.chunking$typeTable().setProto(packed);
                this.markPositionReplaceable(pos);
                cir.setReturnValue(Boolean.FALSE);
                return;
            }
        }
        RegionMask saved = this.chunking$savedChunks;
        if ((saved == null || !saved.contains(packed))
                && ((SimpleRegionStorageAccessor) (Object) this).chunking$getWorker()
                instanceof RegionStorageAccess access
                && !access.chunking$hasPreExistingRegionFile(pos.getRegionX(), pos.getRegionZ())) {
            this.chunking$typeTable().setProto(packed);
            this.markPositionReplaceable(pos);
            cir.setReturnValue(Boolean.FALSE);
        }
    }

    @WrapMethod(method = "scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;")
    private CompletableFuture<ChunkAccess> chunking$fastLoadFromRecentSavedData(
            ChunkPos pos,
            Operation<CompletableFuture<ChunkAccess>> original
    ) {
        long packedPos = pos.pack();
        RecentChunks.LoadResult res = this.chunking$recentCache().acquireForLoad(packedPos);
        if (res == null) {
            ConcurrentHashMap<Long, CompletableFuture<Void>> inFlight = this.chunking$inFlightSaves;
            CompletableFuture<Void> pendingSave = inFlight != null ? inFlight.get(Long.valueOf(packedPos)) : null;
            if (pendingSave != null && !pendingSave.isDone()) {
                return pendingSave.handle((v, e) -> null)
                        .thenComposeAsync(ignored -> original.call(pos), this.mainThreadExecutor);
            }
            RegionMask saved = this.chunking$savedChunks;
            if ((saved == null || !saved.contains(packedPos))
                    && ((SimpleRegionStorageAccessor) (Object) this).chunking$getWorker()
                    instanceof RegionStorageAccess access
                    && !access.chunking$hasPreExistingRegionFile(pos.getRegionX(), pos.getRegionZ())) {
                this.poiManager.prefetch(pos);
                return CompletableFuture.completedFuture(this.createEmptyChunk(pos));
            }
            return original.call(pos);
        }
        CompletableFuture<?> poiFuture = this.poiManager.prefetch(pos);
        return res.serializeDone()
                .handle((ignored, err) -> {
                    if (err != null) {
                        return null;
                    }
                    return res.data();
                })
                .thenCombineAsync(poiFuture, (data, ignored) -> {
                    if (data == null) {
                        return null;
                    }
                    net.minecraft.util.profiling.Profiler.get().incrementCounter("chunkLoad");
                    ChunkAccess chunk = data.read(
                            this.level,
                            this.poiManager,
                            ((SimpleRegionStorage) (Object) this).storageInfo(),
                            pos
                    );
                    this.markPosition(pos, chunk.getPersistedStatus().getChunkType());
                    return chunk;
                }, this.mainThreadExecutor)
                .thenCompose(chunk -> chunk != null ? CompletableFuture.completedFuture(chunk) : original.call(pos))
                .exceptionallyAsync(ex -> this.handleChunkLoadFailure(ex, pos), this.mainThreadExecutor);
    }

    // Use pendingUnloads.size() in O(1) instead of ConcurrentLinkedQueue.size(), skipping overflow drain on live MSPT spikes.
    @Redirect(
            method = "processUnloads",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Queue;size()I"
            )
    )
    private int chunking$fastUnloadQueueSize(Queue<?> queue) {
        if (queue == this.unloadQueue) {
            if (this.chunking$inSaveAllFlush) {
                return this.pendingUnloads.size();
            }
            int sz = this.pendingUnloads.size();
            FrameGuard gov = FrameGuard.get();
            if (gov.hasActiveClientFrames()) {
                if (sz > 2048) {
                    return 2000 + Math.min(64, ((sz - 2048) >> 5) + 16);
                }
                if (sz > 512) {
                    return 2000 + Math.min(16, ((sz - 512) >> 5) + 4);
                }
                return sz > 192 ? 2000 + Math.min(4, ((sz - 192) >> 4) + 1) : 0;
            }
            if (!this.level.players().isEmpty()) {
                if (gov.isServerTickPressured() || TickBudget.lastRecordedTickMs() > 25.0D) {
                    return sz > 3072 ? 2000 + Math.min(8, ((sz - 3072) >> 6) + 1) : 0;
                }
                return sz > 1536 ? 2000 + Math.min(32, ((sz - 1536) >> 4) + 4) : 0;
            }
            return sz > 1024 ? 2000 + Math.min(256, ((sz - 1024) >> 3) + 24) : 0;
        }
        return queue.size();
    }

    @Redirect(
            method = "processUnloads",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;saveChunksEagerly(Ljava/util/function/BooleanSupplier;)V"
            ),
            require = 0
    )
    private void chunking$skipMidTickEagerAutosaveDuringMotion(ChunkMap instance, BooleanSupplier hasTimeLeft) {
        if (this.activeChunkWrites.get() > 16
                || this.pendingUnloads.size() > 32
                || TaskQueue.inFlightCount() > 4
                || FrameGuard.get().isServerTickPressured()
                || TickBudget.lastRecordedTickMs() > 25.0D) {
            return;
        }
        MoveTracker.PlayerMotion[] motions =
                ChunkEngine.get().velocityTracker().getAnyActiveMotions();
        if (motions != null) {
            for (int i = 0; i < motions.length; i++) {
                MoveTracker.PlayerMotion m = motions[i];
                if (m != null && m.speedXZ >= 0.15D) {
                    return;
                }
            }
        }
        this.saveChunksEagerly(hasTimeLeft);
    }
}
