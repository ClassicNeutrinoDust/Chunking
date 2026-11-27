package com.chunking.engine.core;

import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.AreaLock;
import com.chunking.engine.scheduler.MoveTracker;
import com.chunking.engine.scheduler.WorkerPool;

/**
 * Root runtime coordinator for the Chunking engine.
 */
public final class ChunkEngine implements AutoCloseable {
    private static final ChunkEngine INSTANCE = new ChunkEngine();

    private final WorkerPool workerPool = new WorkerPool();
    private final AreaLock featureLock = new AreaLock();
    private final MoveTracker velocityTracker = new MoveTracker();
    private final StageTimer stageProfiler = new StageTimer();
    private volatile boolean closed = false;

    private ChunkEngine() {}

    public static ChunkEngine get() { return INSTANCE; }

    public WorkerPool workerPool() { return workerPool; }
    public AreaLock featureLock() { return featureLock; }
    public MoveTracker velocityTracker() { return velocityTracker; }
    public StageTimer stageProfiler() { return stageProfiler; }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                FrameGuard.get().stop();
            } catch (Throwable ignored) {
            }
            try {
                workerPool.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
