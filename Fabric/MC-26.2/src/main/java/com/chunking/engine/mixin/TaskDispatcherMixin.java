package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.ChunkTaskDispatcherAccessor;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.TaskQueue;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.world.FastLongSet;
import com.chunking.engine.access.TaskDispatcherAccess;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.ThrottlingChunkTaskDispatcher;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.util.thread.TaskScheduler;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

@Mixin(ChunkTaskDispatcher.class)
public abstract class TaskDispatcherMixin implements TaskDispatcherAccess {
    @Shadow
    @Final
    private PriorityConsecutiveExecutor dispatcher;

    @Shadow
    @Final
    private TaskScheduler<Runnable> executor;

    @Shadow
    protected boolean sleeping;

    @Unique
    private static final Logger CHUNKING$LOGGER = LoggerFactory.getLogger("Chunking");

    @Unique
    private AtomicInteger chunking$inFlightBatches;

    @Unique
    private AtomicBoolean chunking$pumpScheduled;

    @Unique
    private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap chunking$activeCounts;

    @Unique
    private Object chunking$activeLock;

    @Unique
    private volatile boolean chunking$closed;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initState(CallbackInfo ci) {
        this.chunking$inFlightBatches = new AtomicInteger();
        this.chunking$pumpScheduled = new AtomicBoolean();
        this.chunking$activeCounts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(64);
        this.chunking$activeLock = new Object();
    }

    // Only ChunkMap's worldgenDispatcher should pump parallel batches; light and ticket throttler stay serial.
    @Unique
    private boolean chunking$isExcludedDispatcher() {
        return (Object) this instanceof ThrottlingChunkTaskDispatcher
                || this.executor instanceof ThreadedLevelLightEngine
                || "light".equals(this.executor.name());
    }

    @Unique
    private static int chunking$effectiveMaxInFlight() {
        int workers = Math.max(1, ModConfig.WORLDGEN_WORKERS);
        FrameGuard gov = FrameGuard.get();
        if (gov.hasActiveClientFrames() || ModConfig.isClientEnvironment()) {
            if (gov.isServerTickPressured()) {
                return Math.max(8, Math.min(64, workers * 4));
            }
            return Math.max(16, Math.min(128, workers * 8));
        }
        if (TickBudget.hasOnlinePlayers()) {
            if (gov.level() == FrameGuard.Level.SEVERE || TickBudget.lastRecordedTickMs() > 35.0D) {
                return Math.max(4, workers * 2);
            }
            if (gov.isServerTickPressured()) {
                return Math.max(6, workers * 4);
            }
        }
        if (workers <= 2) {
            return workers * 8 + 4;
        }
        return Math.min(2048, workers * 12);
    }

    @Override
    public boolean chunking$isPosActive(long chunkPos) {
        synchronized (this.chunking$activeLock) {
            return this.chunking$activeCounts != null && this.chunking$activeCounts.containsKey(chunkPos);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void chunking$onClose(CallbackInfo ci) {
        this.chunking$closed = true;
    }

    // Skip onLevelChange if this chunk has no queued work here (most chunks during a border crossing).
    @Inject(method = "onLevelChange", at = @At("HEAD"), cancellable = true)
    private void chunking$shortCircuitInactiveLevelChange(
            ChunkPos pos,
            IntSupplier oldLevel,
            int newLevel,
            IntConsumer setQueueLevel,
            CallbackInfo ci
    ) {
        int prev = oldLevel.getAsInt();
        if (prev == newLevel) {
            ci.cancel();
            return;
        }
        long packed = pos.pack();
        boolean active;
        synchronized (this.chunking$activeLock) {
            active = this.chunking$activeCounts.containsKey(packed);
        }
        if (!active) {
            setQueueLevel.accept(newLevel);
            ci.cancel();
        }
    }

    @Inject(method = "popTasks", at = @At("RETURN"))
    private void chunking$untrackPoppedChunkPos(CallbackInfoReturnable<ChunkTaskPriorityQueue.TasksForChunk> cir) {
        ChunkTaskPriorityQueue.TasksForChunk popped = cir.getReturnValue();
        if (popped != null) {
            long packed = popped.chunkPos();
            int removed = popped.tasks().size();
            synchronized (this.chunking$activeLock) {
                int cur = this.chunking$activeCounts.get(packed);
                if (cur <= removed) {
                    this.chunking$activeCounts.remove(packed);
                } else {
                    this.chunking$activeCounts.put(packed, cur - removed);
                }
            }
        }
    }

    @Inject(method = "pollTask", at = @At("HEAD"), cancellable = true)
    private void chunking$parallelPollTask(CallbackInfo ci) {
        if (this.chunking$closed || chunking$isExcludedDispatcher()) {
            return;
        }
        ci.cancel();
        chunking$pumpQueueBatches();
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void chunking$trackSubmittedChunkPos(Runnable runnable, long pos, IntSupplier level, CallbackInfo ci) {
        synchronized (this.chunking$activeLock) {
            this.chunking$activeCounts.addTo(pos, 1);
        }
    }

    @Inject(method = "submit", at = @At("TAIL"))
    private void chunking$onSubmitWakeParallelPump(Runnable runnable, long pos, IntSupplier level, CallbackInfo ci) {
        if (this.chunking$closed || chunking$isExcludedDispatcher()) {
            return;
        }
        if (this.chunking$inFlightBatches.get() < chunking$effectiveMaxInFlight()) {
            chunking$schedulePump();
        }
    }

    @Inject(method = "release", at = @At("TAIL"))
    private void chunking$onReleaseWakeParallelPump(long pos, Runnable postRelease, boolean clearQueue, CallbackInfo ci) {
        if (clearQueue) {
            if (this.chunking$closed) {
                synchronized (this.chunking$activeLock) {
                    this.chunking$activeCounts.remove(pos);
                }
            } else {
                // Schedule removal at priority 0 so any earlier async onLevelChange for pos finishes first.
                this.dispatcher.schedule(new StrictQueue.RunnableWithPriority(0, () -> {
                    synchronized (this.chunking$activeLock) {
                        this.chunking$activeCounts.remove(pos);
                    }
                }));
            }
        }
        if (this.chunking$closed || chunking$isExcludedDispatcher()) {
            return;
        }
        if (this.chunking$inFlightBatches.get() < chunking$effectiveMaxInFlight()) {
            chunking$schedulePump();
        }
    }

    @Unique
    private void chunking$schedulePump() {
        if (this.chunking$closed || !this.chunking$pumpScheduled.compareAndSet(false, true)) {
            return;
        }
        this.dispatcher.schedule(new StrictQueue.RunnableWithPriority(2, () -> {
            this.chunking$pumpScheduled.set(false);
            chunking$pumpQueueBatches();
        }));
    }

    @Unique
    private void chunking$pumpQueueBatches() {
        if (this.chunking$closed) {
            return;
        }
        ChunkTaskDispatcherAccessor access = (ChunkTaskDispatcherAccessor) this;
        int limit = chunking$effectiveMaxInFlight();
        AtomicInteger inFlight = this.chunking$inFlightBatches;

        try {
            while (inFlight.get() < limit) {
                ChunkTaskPriorityQueue.TasksForChunk batch = access.chunking$invokePopTasks();
                if (batch == null) {
                    return;
                }
                inFlight.incrementAndGet();
                chunking$dispatchBatch(batch);
            }
            if (inFlight.get() < limit && access.chunking$getQueue().hasWork()) {
                chunking$schedulePump();
            }
        } finally {
            this.sleeping = true;
        }
    }

    @Unique
    private void chunking$dispatchBatch(ChunkTaskPriorityQueue.TasksForChunk batch) {
        List<Runnable> tasks = batch.tasks();
        AtomicInteger inFlight = this.chunking$inFlightBatches;
        int n = tasks.size();
        if (n == 0) {
            inFlight.decrementAndGet();
            chunking$schedulePump();
            return;
        }
        if (n == 1) {
            Runnable runnable = tasks.get(0);
            try {
                this.executor.schedule(() -> chunking$runBatchTask(runnable, () -> {
                    inFlight.decrementAndGet();
                    chunking$schedulePump();
                }));
            } catch (Throwable t) {
                inFlight.decrementAndGet();
                throw t;
            }
            return;
        }
        chunking$scheduleBatchStep(tasks, 0, inFlight);
    }

    @Unique
    private void chunking$scheduleBatchStep(List<Runnable> tasks, int index, AtomicInteger inFlight) {
        if (index >= tasks.size()) {
            inFlight.decrementAndGet();
            chunking$schedulePump();
            return;
        }
        Runnable runnable = tasks.get(index);
        try {
            this.executor.schedule(() -> chunking$runBatchTask(runnable,
                    () -> chunking$scheduleBatchStep(tasks, index + 1, inFlight)));
        } catch (Throwable t) {
            inFlight.decrementAndGet();
            throw t;
        }
    }

    @Unique
    private void chunking$runBatchTask(Runnable runnable, Runnable onComplete) {
        CompletableFuture<?> capturedStage = null;
        try {
            TaskQueue.pollCapturedStageFuture();
            runnable.run();
        } catch (Throwable t) {
            CHUNKING$LOGGER.error("[Chunking] Task batch execution failed on {}", this.executor.name(), t);
            if (t instanceof net.minecraft.ReportedException re) {
                CHUNKING$LOGGER.error("[Chunking] Detailed CrashReport:\n{}",
                        re.getReport().getFriendlyReport(net.minecraft.ReportType.CRASH));
            }
            throw t;
        } finally {
            capturedStage = TaskQueue.pollCapturedStageFuture();
            if (capturedStage != null && !capturedStage.isDone()) {
                capturedStage.whenComplete((res, err) -> onComplete.run());
            } else {
                onComplete.run();
            }
        }
    }
}
