package com.chunking.engine.scheduler;

import com.chunking.engine.config.ModConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * ForkJoinPool for parallel worldgen workers, prioritized below the server and render threads.
 */
public final class WorkerPool implements AutoCloseable {
    private static final long[] EMPTY_THREAD_IDS = new long[0];

    public static int workerThreadPriority(boolean clientEnvironment) {
        // Server Tick Thread already runs at NORM_PRIORITY + 2; keeping dedicated workers at NORM_PRIORITY
        // prevents Windows 11 Intel Thread Director from steering BELOW_NORMAL workers onto E-cores.
        return clientEnvironment
                ? Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2)
                : Thread.NORM_PRIORITY;
    }

    private static final class ChunkingForkJoinWorkerThread extends ForkJoinWorkerThread {
        final WorkerPool owner;

        ChunkingForkJoinWorkerThread(ForkJoinPool pool, WorkerPool owner, int index) {
            super(pool);
            this.owner = owner;
            setName("Chunking-Worker-" + index);
            setPriority(workerThreadPriority(ModConfig.isClientEnvironment()));
            setDaemon(true);
        }

        @Override
        protected void onStart() {
            super.onStart();
            owner.registerWorkerThread(threadId());
            if (!ModConfig.isClientEnvironment()) {
                com.chunking.engine.core.ThreadHints.preferHighPerformanceCurrentThread();
            }
        }

        @Override
        protected void onTermination(Throwable exception) {
            owner.unregisterWorkerThread(threadId());
            super.onTermination(exception);
        }
    }

    private volatile ForkJoinPool pool;
    private volatile int workers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int maxConcurrentDispatches;
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final AtomicInteger threadIds = new AtomicInteger(1);
    private final Set<Long> liveWorkerThreadIds = ConcurrentHashMap.newKeySet();
    private volatile long[] activeWorkerThreadIdsSnapshot = EMPTY_THREAD_IDS;

    private final Executor cachedExecutor = command -> {
        ForkJoinPool targetPool = this.pool;
        if (this.closed.get() || targetPool == null || targetPool.isShutdown()) {
            command.run();
            return;
        }
        this.submittedTasks.increment();
        try {
            targetPool.execute(() -> {
                try {
                    command.run();
                } finally {
                    this.completedTasks.increment();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            command.run();
            this.completedTasks.increment();
        }
    };

    private final Executor cachedInlineExecutor = command -> {
        if (this.closed.get()
                || (Thread.currentThread() instanceof ChunkingForkJoinWorkerThread cwt && cwt.owner == this)) {
            command.run();
            return;
        }
        this.cachedExecutor.execute(command);
    };

    public WorkerPool() {
        this(ModConfig.WORLDGEN_WORKERS, ModConfig.MAX_CONCURRENT_DISPATCHES);
    }

    public WorkerPool(int parallelism, int maxConcurrentDispatches) {
        this.workers = Math.max(1, Math.min(256, parallelism));
        this.maxConcurrentDispatches = Math.max(this.workers * 3, maxConcurrentDispatches);
        this.pool = createPool(this.workers);
    }

    private ForkJoinPool createPool(int targetWorkers) {
        int threads = Math.max(1, Math.min(256, targetWorkers));
        ForkJoinPool.ForkJoinWorkerThreadFactory factory =
                fjp -> new ChunkingForkJoinWorkerThread(fjp, this, this.threadIds.getAndIncrement());
        return new ForkJoinPool(threads, factory, null, true);
    }

    public synchronized void setParallelism(int newParallelism) {
        int clamped = Math.max(1, Math.min(256, newParallelism));
        ModConfig.WORLDGEN_WORKERS = clamped;
        if (closed.get() || clamped == this.workers) {
            return;
        }
        ForkJoinPool previous = this.pool;
        this.workers = clamped;
        this.maxConcurrentDispatches = Math.max(clamped * 3, ModConfig.effectiveMaxConcurrentDispatches());
        this.pool = createPool(clamped);
        if (previous != null) {
            previous.shutdown();
        }
    }

    public Executor executor() {
        return this.cachedExecutor;
    }

    /**
     * When already inside a stage task dispatched onto a ChunkingForkJoinWorkerThread, executes
     * createBiomes / fillFromNoise directly on that worker thread without allocating a second async task
     * or holding extra NoiseChunks in flight.
     */
    public Executor inlineIfWorkerExecutor() {
        return this.cachedInlineExecutor;
    }

    public int parallelism() { return this.workers; }
    public int maxConcurrentDispatches() { return maxConcurrentDispatches; }
    public long submitted() { return submittedTasks.sum(); }
    public long completed() { return completedTasks.sum(); }
    public long queueDepth() { return pool.getQueuedSubmissionCount() + pool.getQueuedTaskCount(); }

    /** Cached snapshot of currently live worker thread IDs (rebuilt only on thread start/exit). */
    public long[] activeWorkerThreadIds() {
        return this.activeWorkerThreadIdsSnapshot;
    }

    private void registerWorkerThread(long id) {
        this.liveWorkerThreadIds.add(id);
        rebuildActiveWorkerIds();
    }

    private void unregisterWorkerThread(long id) {
        this.liveWorkerThreadIds.remove(id);
        rebuildActiveWorkerIds();
    }

    private synchronized void rebuildActiveWorkerIds() {
        int size = this.liveWorkerThreadIds.size();
        if (size == 0) {
            this.activeWorkerThreadIdsSnapshot = EMPTY_THREAD_IDS;
            return;
        }
        long[] ids = new long[size];
        int i = 0;
        for (Long id : this.liveWorkerThreadIds) {
            if (i == ids.length) {
                ids = java.util.Arrays.copyOf(ids, ids.length + 4);
            }
            ids[i++] = id;
        }
        this.activeWorkerThreadIdsSnapshot = (i == ids.length) ? ids : java.util.Arrays.copyOf(ids, i);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pool.shutdown();
        }
    }
}
