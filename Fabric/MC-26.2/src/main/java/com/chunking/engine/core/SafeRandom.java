package com.chunking.engine.core;

import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

import java.util.function.Supplier;

/**
 * Thread-safe drop-in replacement for {@code Level.random}: executes zero-overhead unsynchronized
 * LCG steps on the owning level thread and routes any off-thread access (from mods or parallel
 * worldgen) to an isolated per-thread {@link SingleThreadedRandomSource} instead of throwing
 * {@code Accessing LegacyRandomSource from multiple threads} or corrupting {@code MarsagliaPolarGaussian}.
 */
public final class SafeRandom extends SingleThreadedRandomSource {
    private static final ThreadLocal<SingleThreadedRandomSource> FALLBACK =
            ThreadLocal.withInitial(() -> new SingleThreadedRandomSource(RandomSupport.generateUniqueSeed()));

    private final Supplier<Thread> ownerThread;
    private volatile Thread cachedOwner;

    public SafeRandom(long seed, Supplier<Thread> ownerThread) {
        super(seed);
        this.ownerThread = ownerThread;
    }

    private boolean isOwnerThread() {
        Thread owner = this.cachedOwner;
        if (owner != null) {
            return Thread.currentThread() == owner;
        }
        Supplier<Thread> sup = this.ownerThread;
        if (sup == null) {
            return true;
        }
        owner = sup.get();
        if (owner == null) {
            return true;
        }
        this.cachedOwner = owner;
        return Thread.currentThread() == owner;
    }

    @Override
    public void setSeed(long seed) {
        if (isOwnerThread()) {
            super.setSeed(seed);
        } else {
            FALLBACK.get().setSeed(seed);
        }
    }

    @Override
    public int next(int bits) {
        if (isOwnerThread()) {
            return super.next(bits);
        }
        return FALLBACK.get().next(bits);
    }

    @Override
    public double nextGaussian() {
        if (isOwnerThread()) {
            return super.nextGaussian();
        }
        return FALLBACK.get().nextGaussian();
    }
}
