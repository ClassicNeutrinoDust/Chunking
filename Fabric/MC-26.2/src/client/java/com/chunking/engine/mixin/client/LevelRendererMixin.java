package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.mixin.MixinPlugin;
import com.chunking.engine.scheduler.FrameGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the frame-rate governor, initializes the unified per-frame streaming budget, and
 * suppresses render-thread synchronous section compiles during fast flight or non-CALM frame
 * headroom without injecting any custom draw passes (keeping Iris, Voxy, and Distant Horizons
 * rendering pipelines 100% untouched).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Unique
    private long chunking$syncCompileDeadlineNs;

    @Unique
    private int chunking$syncCompilesThisFrame;

    @Unique
    private int chunking$maxSyncCompilesThisFrame;

    @Unique
    private boolean chunking$boostedRenderPriority;

    @Inject(method = "compileSections", at = @At("HEAD"), require = 0)
    private void chunking$beginCompileSectionsFrame(CameraRenderState cameraRenderState, CallbackInfo ci) {
        this.chunking$syncCompilesThisFrame = 0;
        ClientRuntime runtime = ClientRuntime.get();
        FrameGuard gov = FrameGuard.get();
        if (runtime.isMovingFast()
                || runtime.isTurningFast()
                || gov.isInitialWorldLoadWindow()
                || gov.isFramePressured()
                || gov.isInFrameHitch()
                || ClientRuntime.isInLocalFrameSpike()
                || ClientRuntime.isInGuiTransition()
                || ClientRuntime.isStreamingBudgetExhausted()
                || gov.level() != FrameGuard.Level.CALM) {
            this.chunking$maxSyncCompilesThisFrame = 0;
            this.chunking$syncCompileDeadlineNs = 0L;
        } else {
            long syncBudgetNs = ClientRuntime.remainingStreamingBudgetNs(600_000L);
            if (syncBudgetNs < 150_000L) {
                this.chunking$maxSyncCompilesThisFrame = 0;
                this.chunking$syncCompileDeadlineNs = 0L;
            } else {
                this.chunking$maxSyncCompilesThisFrame = 1;
                this.chunking$syncCompileDeadlineNs = System.nanoTime() + syncBudgetNs;
            }
        }
    }

    /**
     * Prevents non-player-edited arriving/lit chunks under {@link PrioritizeChunkUpdates#NEARBY}
     * from running synchronous render-thread {@code compileSync} rebuilds while keeping instant
     * fade-in disabled for nearby sections.
     */
    @WrapOperation(
            method = "compileSections",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;prioritizeChunkUpdates:Lnet/minecraft/client/PrioritizeChunkUpdates;",
                    opcode = Opcodes.GETFIELD
            ),
            require = 0
    )
    private PrioritizeChunkUpdates chunking$avoidStreamingSyncCompiles(
            OptionsRenderState options,
            Operation<PrioritizeChunkUpdates> original
    ) {
        PrioritizeChunkUpdates mode = original.call(options);
        if (MixinPlugin.isSodiumLoaded() || MixinPlugin.isVulkanModLoaded()) {
            return mode;
        }
        return mode == PrioritizeChunkUpdates.NEARBY ? PrioritizeChunkUpdates.PLAYER_AFFECTED : mode;
    }

    /**
     * Offloads all section rebuilds to asynchronous workers during flight or frame-time pressure,
     * and bounds stationary player-initiated synchronous rebuilds to at most 1 section / 0.6 ms per frame
     * accounted against the unified streaming budget.
     */
    @WrapOperation(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
            ),
            require = 0
    )
    private void chunking$budgetSyncSectionCompile(
            SectionRenderDispatcher.RenderSection section,
            RenderSectionRegion region,
            Operation<Void> original
    ) {
        if (MixinPlugin.isSodiumLoaded() || MixinPlugin.isVulkanModLoaded()) {
            original.call(section, region);
            return;
        }
        long nowNs = System.nanoTime();
        if (this.chunking$syncCompilesThisFrame < this.chunking$maxSyncCompilesThisFrame
                && nowNs < this.chunking$syncCompileDeadlineNs) {
            this.chunking$syncCompilesThisFrame++;
            original.call(section, region);
            ClientRuntime.consumeStreamingBudget(System.nanoTime() - nowNs);
        } else {
            section.compileAsync(region);
        }
    }

    @Unique
    private long chunking$lastRenderPassNs;

    @Inject(method = "render", at = @At("HEAD"))
    private void chunking$updateCameraAndGovernor(
            GraphicsResourceAllocator allocator,
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            CameraRenderState cameraRenderState,
            Matrix4fc modelViewMatrix,
            GpuBufferSlice fogBuffer,
            Vector4f skyColor,
            boolean shouldRenderSky,
            CallbackInfo ci
    ) {
        if (!this.chunking$boostedRenderPriority) {
            this.chunking$boostedRenderPriority = true;
            try {
                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                com.chunking.engine.core.ThreadHints.preferHighPerformanceCurrentThread();
            } catch (Throwable ignored) {
            }
        }
        long nowNs = System.nanoTime();
        // Skip duplicate render-pass invocations (shadow/mirror passes) so frame baselines stay accurate.
        if (this.chunking$lastRenderPassNs != 0L && (nowNs - this.chunking$lastRenderPassNs) < 500_000L) {
            return;
        }
        this.chunking$lastRenderPassNs = nowNs;
        FrameGuard gov = FrameGuard.get();
        gov.onFrame();
        ClientRuntime.onRenderFrame(nowNs, gov.frameBaselineMs());
        com.chunking.engine.core.LiveStats.get().onFrame(nowNs);

        long frameStreamingBudgetNs = ClientRuntime.computeFrameStreamingBudgetNs(gov);
        ClientRuntime.beginFrameStreamingBudget(frameStreamingBudgetNs);

        if (cameraRenderState == null || cameraRenderState.pos == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.level == null) {
            return;
        }

        ClientRuntime.get().updateCameraMotion(
                cameraRenderState.pos.x,
                cameraRenderState.pos.y,
                cameraRenderState.pos.z,
                cameraRenderState.yRot,
                cameraRenderState.xRot,
                nowNs
        );
    }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void chunking$onResetLevelRenderData(CallbackInfo ci) {
        ClientRuntime.get().onWorldReset();
    }
}
