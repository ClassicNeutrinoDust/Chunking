package com.chunking.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Guards WoodlandMansionPieces$SimpleGrid reads/writes with a ReentrantReadWriteLock.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces$SimpleGrid")
public abstract class WoodlandMansionSimpleGridMixin {
    // Initialized in <init> (mixin field)
    @Unique
    private volatile ReentrantReadWriteLock chunking$rwLock;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initRwLock(CallbackInfo ci) {
        this.chunking$rwLock = new ReentrantReadWriteLock();
    }

    @WrapMethod(method = "set(III)V")
    private void chunking$syncSet3(int x, int y, int value, Operation<Void> original) {
        ReentrantReadWriteLock lock = this.chunking$rwLock;
        lock.writeLock().lock();
        try {
            original.call(x, y, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @WrapMethod(method = "set(IIIII)V")
    private void chunking$syncSet5(int x0, int y0, int x1, int y1, int value, Operation<Void> original) {
        ReentrantReadWriteLock lock = this.chunking$rwLock;
        lock.writeLock().lock();
        try {
            original.call(x0, y0, x1, y1, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @WrapMethod(method = "get(II)I")
    private int chunking$syncGet(int x, int y, Operation<Integer> original) {
        ReentrantReadWriteLock lock = this.chunking$rwLock;
        lock.readLock().lock();
        try {
            return original.call(x, y);
        } finally {
            lock.readLock().unlock();
        }
    }

    @WrapMethod(method = "edgesTo(III)Z")
    private boolean chunking$syncEdgesTo(int x, int y, int expectedValue, Operation<Boolean> original) {
        ReentrantReadWriteLock lock = this.chunking$rwLock;
        lock.readLock().lock();
        try {
            return original.call(x, y, expectedValue);
        } finally {
            lock.readLock().unlock();
        }
    }
}
