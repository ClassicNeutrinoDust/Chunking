package com.chunking.engine.io;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import java.util.concurrent.locks.ReentrantLock;

/** Serializes decode/encode calls on shared non-thread-safe codecs. */
public final class SyncCodec<A> implements Codec<A> {
    private final ReentrantLock lock = new ReentrantLock(false);
    private final Codec<A> delegate;

    public SyncCodec(Codec<A> delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
        this.lock.lock();
        try {
            return this.delegate.decode(ops, input);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
        this.lock.lock();
        try {
            return this.delegate.encode(input, ops, prefix);
        } finally {
            this.lock.unlock();
        }
    }
}
