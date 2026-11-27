package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.mixin.accessor.ChunkMapAccessor;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.MoveTracker;
import com.chunking.engine.scheduler.TaskQueue;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.access.ChunkHolderLightAccess;
import com.chunking.engine.access.SyncWaitDepthAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Coalesces off-thread light updates, budgets between-tick pollTask draining under load,
 * and pulses sub-tick chunk streaming when tick headroom is healthy.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements SyncWaitDepthAccess {
    private static final long HOLDER_MAP_UPDATE_INTERVAL_NS = 50_000_000L;  // 50 ms
    private static final long BG_DISTANCE_UPDATE_INTERVAL_NS = 12_000_000L; // 12 ms coalescing in pollTask()
    private static final long SUB_TICK_STREAM_INTERVAL_NS = 12_500_000L;    // 12.5 ms (80 Hz sub-tick streaming)
    private static final long SUB_TICK_STREAM_BUDGET_NS = 3_000_000L;       // 3 ms cap per sub-tick send burst
    private static final long TICK_WALL_YIELD_NS = 35_000_000L;             // yield between-tick drain past 35 ms

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Thread mainThread;

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    @Final
    private Set<ChunkHolder> chunkHoldersToBroadcast;

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long chunkPos);

    @Shadow
    protected abstract boolean runDistanceManagerUpdates();

    @Unique
    private long chunking$lastHolderUpdateNs;

    @Unique
    private long chunking$lastDistanceUpdateNs;

    @Unique
    private long chunking$lastSubTickStreamNs;

    @Unique
    private boolean chunking$inServerTick;

    @Unique
    private boolean chunking$boostedThreadPriority;

    @Unique
    private int chunking$inPollTaskDepth;

    @Unique
    private int chunking$syncChunkWaitDepth;

    @Unique
    private long chunking$tickStartNs;

    @Unique
    private long chunking$pollDrainNsThisTick;

    @Unique
    private int chunking$pollTasksThisTick;

    @Unique
    private int chunking$subTickPlayerCursor;

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Ljava/util/concurrent/Executor;Lnet/minecraft/util/thread/BlockableEventLoop;Lnet/minecraft/world/level/chunk/LightChunkGetter;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/entity/ChunkStatusUpdateListener;Ljava/util/function/Supplier;Lnet/minecraft/world/level/TicketStorage;IZ)V"
            ),
            index = 4
    )
    private Executor chunking$replaceWorldgenExecutor(Executor originalExecutor) {
        return ChunkEngine.get().workerPool().executor();
    }

    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void chunking$coalesceLightUpdate(LightLayer type, SectionPos pos, CallbackInfo ci) {
        ci.cancel();
        ChunkHolder chunkHolder = this.getVisibleChunkIfPresent(pos.chunk().pack());
        if (chunkHolder == null || chunkHolder.getLatestChunk() == null) {
            return;
        }
        ChunkHolderLightAccess access = (ChunkHolderLightAccess) chunkHolder;
        access.chunking$queueLightSectionDirty(type, pos.y());
        if (access.chunking$shouldScheduleUndirty()) {
            ((ChunkMapAccessor) this.chunkMap).chunking$getMainThreadExecutor().execute(() -> {
                if (access.chunking$undirtyLight()) {
                    this.chunkHoldersToBroadcast.add(chunkHolder);
                }
            });
        }
    }

    @Override
    public void chunking$enterSyncWait() {
        this.chunking$syncChunkWaitDepth++;
    }

    @Override
    public void chunking$exitSyncWait() {
        if (this.chunking$syncChunkWaitDepth > 0) {
            this.chunking$syncChunkWaitDepth--;
        }
    }

    @Redirect(
            method = "getChunkFutureMainThread",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;runDistanceManagerUpdates()Z"
            )
    )
    private boolean chunking$neverThrottleGetChunkFutureUpdates(ServerChunkCache instance) {
        this.chunking$syncChunkWaitDepth++;
        try {
            return ((ServerChunkCacheMixin) (Object) instance).runDistanceManagerUpdates();
        } finally {
            if (this.chunking$syncChunkWaitDepth > 0) {
                this.chunking$syncChunkWaitDepth--;
            }
        }
    }

    @Inject(method = "addTicket", at = @At("HEAD"), require = 0)
    private void chunking$resetCoalesceOnDirectAddTicket(Ticket ticket, ChunkPos pos, CallbackInfo ci) {
        this.chunking$lastDistanceUpdateNs = 0L;
        this.chunking$lastHolderUpdateNs = 0L;
    }

    @Inject(method = "addTicketWithRadius", at = @At("HEAD"), require = 0)
    private void chunking$resetCoalesceOnAddTicket(TicketType ticketType, ChunkPos pos, int radius, CallbackInfo ci) {
        this.chunking$lastDistanceUpdateNs = 0L;
        this.chunking$lastHolderUpdateNs = 0L;
    }

    @Inject(method = "removeTicketWithRadius", at = @At("HEAD"), require = 0)
    private void chunking$resetCoalesceOnRemoveTicket(TicketType ticketType, ChunkPos pos, int radius, CallbackInfo ci) {
        this.chunking$lastDistanceUpdateNs = 0L;
        this.chunking$lastHolderUpdateNs = 0L;
    }

    // Track pollTask() depth, budget between-tick drains under player load, and pulse sub-tick sends.
    @WrapMethod(method = "pollTask")
    private boolean chunking$wrapPollTask(Operation<Boolean> original) {
        boolean topLevelBetweenTicks = this.chunking$inPollTaskDepth == 0
                && !this.chunking$inServerTick
                && this.chunking$syncChunkWaitDepth <= 0
                && !TickBudget.isInManagedBlock();
        FrameGuard gov = FrameGuard.get();
        boolean clientActive = gov.hasActiveClientFrames();
        List<ServerPlayer> players = this.level.players();
        boolean playersOrClient = clientActive || !players.isEmpty();

        if (topLevelBetweenTicks && playersOrClient) {
            double effectiveMs = TickBudget.lastRecordedTickMs();
            long drainBudgetNs = TickBudget.computePollDrainBudgetNs(
                    true, effectiveMs, gov.isServerTickPressured(), gov.level());
            long tickStart = this.chunking$tickStartNs;
            long tickElapsedNs = tickStart != 0L ? (System.nanoTime() - tickStart) : 0L;
            int minTasks = (effectiveMs >= 35.0D || gov.level() == FrameGuard.Level.SEVERE) ? 2 : 4;
            if (tickStart != 0L
                    && tickElapsedNs < 120_000_000L
                    && this.chunking$pollTasksThisTick >= minTasks
                    && (this.chunking$pollDrainNsThisTick >= drainBudgetNs
                    || tickElapsedNs >= TICK_WALL_YIELD_NS)) {
                return false;
            }
        }

        long callStartNs = (topLevelBetweenTicks && playersOrClient) ? System.nanoTime() : 0L;
        this.chunking$inPollTaskDepth++;
        boolean ran = false;
        try {
            ran = original.call();
            return ran;
        } finally {
            if (this.chunking$inPollTaskDepth > 0) {
                this.chunking$inPollTaskDepth--;
            }
            if (topLevelBetweenTicks) {
                long now = System.nanoTime();
                if (ran && playersOrClient) {
                    this.chunking$pollDrainNsThisTick += Math.max(0L, now - callStartNs);
                    this.chunking$pollTasksThisTick++;
                }
                if (!players.isEmpty()) {
                    long tickStart = this.chunking$tickStartNs;
                    long tickElapsedNs = tickStart != 0L ? (now - tickStart) : 0L;
                    boolean tickHealthy = !gov.isServerTickPressured()
                            && TickBudget.lastRecordedTickMs() <= 28.0D
                            && (tickStart == 0L || tickElapsedNs < 32_000_000L || tickElapsedNs >= 250_000_000L);
                    boolean shouldStream = false;
                    if (tickHealthy && !clientActive && now - this.chunking$lastSubTickStreamNs >= SUB_TICK_STREAM_INTERVAL_NS) {
                        this.chunking$lastSubTickStreamNs = now;
                        shouldStream = true;
                    }
                    if (shouldStream) {
                        int n = players.size();
                        int startIdx = n > 1 ? (this.chunking$subTickPlayerCursor++ % n) : 0;
                        if (startIdx < 0) {
                            startIdx = 0;
                            this.chunking$subTickPlayerCursor = 1;
                        }
                        long streamDeadlineNs = now + SUB_TICK_STREAM_BUDGET_NS;
                        for (int i = 0; i < n; i++) {
                            if (i > 0 && System.nanoTime() >= streamDeadlineNs) {
                                break;
                            }
                            if (i >= players.size()) {
                                break;
                            }
                            ServerPlayer player = players.get((startIdx + i) % players.size());
                            if (player == null || player.connection == null
                                    || player.hasDisconnected() || player.isRemoved()) {
                                continue;
                            }
                            player.connection.suspendFlushing();
                            try {
                                player.connection.chunkSender.sendNextChunks(player);
                            } finally {
                                player.connection.resumeFlushing();
                            }
                        }
                    }
                }
            }
        }
    }

    // Coalesce repeated distance-manager updates inside pollTask() outside managed blocks.
    @WrapMethod(method = "runDistanceManagerUpdates")
    private boolean chunking$coalesceBackgroundDistanceUpdates(Operation<Boolean> original) {
        long now = System.nanoTime();
        if (this.chunking$inPollTaskDepth > 0
                && !this.chunking$inServerTick
                && this.chunking$syncChunkWaitDepth <= 0
                && !TickBudget.isInManagedBlock()) {
            if (now - this.chunking$lastDistanceUpdateNs < BG_DISTANCE_UPDATE_INTERVAL_NS) {
                return false;
            }
        }
        this.chunking$lastDistanceUpdateNs = now;
        return original.call();
    }

    @Redirect(
            method = "runDistanceManagerUpdates",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;promoteChunkMap()Z"
            )
    )
    private boolean chunking$throttlePromoteChunkMapOnTask(ChunkMap instance) {
        long now = System.nanoTime();
        if (this.chunking$inPollTaskDepth <= 0
                || this.chunking$inServerTick
                || this.chunking$syncChunkWaitDepth > 0
                || TickBudget.isInManagedBlock()
                || now - this.chunking$lastHolderUpdateNs >= HOLDER_MAP_UPDATE_INTERVAL_NS) {
            this.chunking$lastHolderUpdateNs = now;
            return ((ChunkMapAccessor) instance).chunking$invokePromoteChunkMap();
        }
        return false;
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
    private void chunking$onBeginServerChunkTick(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        this.chunking$inServerTick = true;
        this.chunking$tickStartNs = System.nanoTime();
        this.chunking$pollDrainNsThisTick = 0L;
        this.chunking$pollTasksThisTick = 0;
        MinecraftServer server = this.level.getServer();
        if (server != null) {
            TickBudget.recordOnlinePlayers(server.getPlayerList().getPlayerCount());
        }
        if (!this.chunking$boostedThreadPriority) {
            this.chunking$boostedThreadPriority = true;
            try {
                boolean isClient = ModConfig.isClientEnvironment();
                int targetPrio = isClient
                        ? (Thread.NORM_PRIORITY + 1)
                        : Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2);
                Thread.currentThread().setPriority(targetPrio);
                com.chunking.engine.core.ThreadHints.preferHighPerformanceCurrentThread();
            } catch (Throwable ignored) {
            }
        }
        MoveTracker tracker = ChunkEngine.get().velocityTracker();
        List<ServerPlayer> players = this.level.players();
        if (players.isEmpty()) {
            if (tracker.getAnyActiveMotions().length != 0) {
                tracker.updateDimension(this.level.dimension().identifier().toString(), MoveTracker.EMPTY_MOTIONS);
            }
            return;
        }
        MoveTracker.PlayerMotion[] motions = new MoveTracker.PlayerMotion[players.size()];
        long gameTime = this.level.getGameTime();
        int dimHash = System.identityHashCode(this.level);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            Vec3 pos = p.position();
            Vec3 vel = p.getDeltaMovement();
            Vec3 look = p.getLookAngle();
            motions[i] = tracker.recordPlayerTick(
                    p.getId(), dimHash, pos.x, pos.y, pos.z, vel.x, vel.z, look.x, look.z, gameTime);
        }
        tracker.updateDimension(this.level.dimension().identifier().toString(), motions);
        if (ModConfig.VELOCITY_CONE_SCHEDULER) {
            TaskQueue.onPlayerMotionsUpdated(motions);
        }
    }

    // Promote visibleChunkMap once per tick right after runDistanceManagerUpdates().
    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;runDistanceManagerUpdates()Z",
                    shift = At.Shift.AFTER
            )
    )
    private void chunking$promoteHolderMapOncePerTick(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        long now = System.nanoTime();
        if (now - this.chunking$lastHolderUpdateNs >= 1_000_000L) {
            this.chunking$lastHolderUpdateNs = now;
            ((ChunkMapAccessor) this.chunkMap).chunking$invokePromoteChunkMap();
        }
    }

    // Broadcast block/light changes on FULL border chunks outside vanilla's ticking range.
    @Redirect(
            method = "broadcastChangedChunks",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"
            )
    )
    private LevelChunk chunking$broadcastBorderFullChunks(ChunkHolder instance) {
        return instance.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("TAIL"))
    private void chunking$onEndServerChunkTick(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        this.chunking$inServerTick = false;
        long now = System.nanoTime();
        this.chunking$lastSubTickStreamNs = now;
        double tickMs = (now - this.chunking$tickStartNs) / 1_000_000.0D;
        MinecraftServer server = this.level.getServer();
        if (server != null) {
            TickBudget.recordDimensionTickMs(server.getTickCount(), tickMs);
        } else {
            TickBudget.recordLastTickMs(tickMs);
        }
        double recordedMs = TickBudget.lastRecordedTickMs();
        if (recordedMs > 25.0D) {
            FrameGuard.get().onServerTickSpikeCheck(recordedMs);
        }
    }
}
