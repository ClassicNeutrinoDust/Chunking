package com.chunking.engine.mixin;

import com.chunking.engine.access.SyncWaitDepthAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * Off-thread lock-free fast-path and exception-safe synchronous {@code managedBlock} depth tracking on
 * {@link ServerChunkCache#getChunk(int, int, ChunkStatus, boolean)}. Disabled automatically
 * by {@link MixinPlugin} when Lithium is installed (Lithium provides its own
 * {@code @Overwrite} on {@code ServerChunkCache.getChunk}).
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheGetChunkMixin {
    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long chunkPos);

    @Inject(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            ),
            cancellable = true
    )
    private void chunking$shortcutOffThreadGetChunk(
            int x,
            int z,
            ChunkStatus leastStatus,
            boolean create,
            CallbackInfoReturnable<ChunkAccess> cir
    ) {
        ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.pack(x, z));
        if (holder != null) {
            ChunkAccess chunk = holder.getChunkIfPresentUnchecked(leastStatus);
            if (chunk instanceof ImposterProtoChunk readOnly) {
                chunk = readOnly.getWrapped();
            }
            if (chunk != null) {
                cir.setReturnValue(chunk);
            }
        }
    }

    @WrapOperation(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"
            )
    )
    private void chunking$wrapSyncChunkWait(
            @Coerce BlockableEventLoop<?> executor,
            BooleanSupplier isDone,
            Operation<Void> original
    ) {
        ((SyncWaitDepthAccess) this).chunking$enterSyncWait();
        try {
            original.call(executor, isDone);
        } finally {
            ((SyncWaitDepthAccess) this).chunking$exitSyncWait();
        }
    }
}
