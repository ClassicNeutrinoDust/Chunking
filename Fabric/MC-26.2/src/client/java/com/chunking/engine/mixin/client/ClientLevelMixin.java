package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.scheduler.FrameGuard;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Deque;

/**
 * Enforces an independent governor-adaptive nanosecond Render-thread budget on
 * {@link ClientLevel#pollLightUpdates()} so light update queues never cause frame spikes
 * during GUI transitions, initial world load, or high-speed chunk streaming.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Shadow
    @Final
    private Deque<Runnable> lightUpdateQueue;

    @Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
    private void chunking$budgetLightUpdates(CallbackInfo ci) {
        int qSize = this.lightUpdateQueue.size();
        if (qSize == 0) {
            ClientRuntime.recordLightRan();
            ci.cancel();
            return;
        }
        FrameGuard gov = FrameGuard.get();
        boolean localSpike = ClientRuntime.isInLocalFrameSpike();
        boolean turningFast = ClientRuntime.get().isTurningFast();
        int target = Math.max(6, Math.min(256, qSize / 6));
        long budgetNs;
        if (ClientRuntime.isInGuiTransition()) {
            budgetNs = 150_000L;
            target = Math.min(target, 12);
        } else if (gov.isInitialWorldLoadWindow()) {
            budgetNs = 300_000L;
            target = Math.min(target, 32);
        } else {
            FrameGuard.Level lvl = gov.level();
            if (lvl == FrameGuard.Level.SEVERE) {
                budgetNs = 250_000L;
                target = Math.min(target, 24);
            } else if (gov.isInFrameHitch() || localSpike) {
                budgetNs = 350_000L;
                target = Math.min(target, 32);
            } else if (lvl == FrameGuard.Level.MILD || gov.isFramePressured()) {
                budgetNs = 420_000L;
                target = Math.min(target, 48);
            } else {
                budgetNs = qSize >= 2000 ? 750_000L : 500_000L;
            }
        }
        // Prevent unbounded queue growth during fast flight/teleports without spiking fast camera turns.
        if (qSize >= 1000 && !ClientRuntime.isInGuiTransition()) {
            if (turningFast) {
                target = Math.max(target, Math.min(qSize, 256));
                budgetNs = Math.max(budgetNs, 800_000L);
            } else {
                target = Math.max(target, Math.min(qSize, 1024));
                budgetNs = Math.max(budgetNs, 2_000_000L);
            }
        }
        if (qSize < 1000 && !ClientRuntime.isInGuiTransition()) {
            long sharedNs = ClientRuntime.remainingStreamingBudgetNs(budgetNs);
            budgetNs = Math.min(budgetNs, Math.max(200_000L, sharedNs));
        }
        long startNs = System.nanoTime();
        for (int i = 0; i < target; i++) {
            Runnable task = this.lightUpdateQueue.poll();
            if (task == null) {
                break;
            }
            task.run();
            if (i >= 1 && (System.nanoTime() - startNs) >= budgetNs) {
                break;
            }
        }
        ClientRuntime.consumeStreamingBudget(System.nanoTime() - startNs);
        ci.cancel();
    }
}
