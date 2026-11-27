package com.chunking.engine.world;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.NoSuchElementException;

/**
 * Non-shrinking {@link LongArrayFIFOQueue} for {@code LightEngine}'s {@code decreaseQueue} and
 * {@code increaseQueue}, eliminating repeated {@code expand()} / {@code reduce()} {@code long[]}
 * reallocations across lighting updates while bounding runaway growth when empty ({@code > 65,536}).
 */
public final class LongQueue extends LongArrayFIFOQueue {
    private static final int MAX_RETAINED_CAPACITY = 65_536;
    private final int minCapacity;

    public LongQueue(int minCapacity) {
        super(Math.max(64, minCapacity));
        this.minCapacity = Math.max(64, minCapacity);
    }

    @Override
    public long dequeueLong() {
        if (this.start == this.end) {
            throw new NoSuchElementException();
        }
        long t = this.array[this.start];
        if (++this.start == this.length) {
            this.start = 0;
        }
        if (this.start == this.end && this.length > MAX_RETAINED_CAPACITY) {
            this.array = new long[this.minCapacity];
            this.length = this.minCapacity;
            this.start = 0;
            this.end = 0;
        }
        return t;
    }

    @Override
    public long dequeueLastLong() {
        if (this.start == this.end) {
            throw new NoSuchElementException();
        }
        if (this.end == 0) {
            this.end = this.length;
        }
        long t = this.array[--this.end];
        if (this.start == this.end && this.length > MAX_RETAINED_CAPACITY) {
            this.array = new long[this.minCapacity];
            this.length = this.minCapacity;
            this.start = 0;
            this.end = 0;
        }
        return t;
    }
}
