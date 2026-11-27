package com.chunking.engine.core;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free nanosecond telemetry and backpressure controller across worldgen, lighting, and HLOD stages.
 * Ensures background HLOD work automatically yields when real chunk generation queues are busy.
 */
public final class StageTimer {
    public enum Stage {
        STRUCTURE_STARTS,
        BIOMES,
        NOISE,
        SURFACE,
        CARVERS,
        FEATURES,
        LIGHT,
        HORIZON_SAMPLE,
        LOD_MESH,
        DISK_IO
    }

    private final LongAdder[] totalNanos = new LongAdder[Stage.values().length];
    private final LongAdder[] completedOps = new LongAdder[Stage.values().length];
    private final LongAdder activeRealWorldgenTasks = new LongAdder();

    public StageTimer() {
        for (int i = 0; i < totalNanos.length; i++) {
            totalNanos[i] = new LongAdder();
            completedOps[i] = new LongAdder();
        }
    }

    public void enterRealWorldgenTask() {
        activeRealWorldgenTasks.increment();
    }

    public void exitRealWorldgenTask() {
        activeRealWorldgenTasks.decrement();
    }

    public long activeRealWorldgenCount() {
        return Math.max(0L, activeRealWorldgenTasks.sum());
    }

    /**
     * Returns true when real worldgen stages are heavily saturated so auxiliary HLOD samplers
     * throttle their batch size to prevent memory/CPU contention.
     */
    public boolean isWorldgenBackpressured(int workers) {
        return activeRealWorldgenCount() > Math.max(8L, workers * 2L);
    }

    public void record(Stage stage, long elapsedNanos) {
        int idx = stage.ordinal();
        totalNanos[idx].add(Math.max(0L, elapsedNanos));
        completedOps[idx].increment();
        LiveStats.get().recordStageCompleted(stage);
    }

    public double averageMillis(Stage stage) {
        int idx = stage.ordinal();
        long ops = completedOps[idx].sum();
        return ops <= 0L ? 0.0D : (totalNanos[idx].sum() / (double) ops) / 1_000_000.0D;
    }

    public long totalOps(Stage stage) {
        return completedOps[stage.ordinal()].sum();
    }
}
