package com.chunking.engine.mixin.client;

import com.chunking.engine.client.ClientRuntime;
import com.chunking.engine.scheduler.FrameGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Selects the nearest eligible dirty sections around the camera (breaking Octree half-space
 * traversal bias) and caps per-frame render-thread {@code RenderRegionCache.createRegion}
 * extraction against its independent budget so chunk-arrival bursts and GUI transitions cannot
 * stall client frames or 0.1%/1% low FPS, while always allowing player-edited sections through immediately.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow
    private ClientLevel level;

    @Shadow
    private SectionUpdateTracker sectionUpdateTracker;

    @Unique
    private long chunking$extractStartNs;

    @Unique
    private int chunking$sectionsExtracted;

    @Unique
    private int chunking$minSections;

    @Unique
    private int chunking$maxSections;

    @Unique
    private long chunking$budgetNs;

    @Unique
    private double chunking$camX;

    @Unique
    private double chunking$camY;

    @Unique
    private double chunking$camZ;

    @Unique
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> chunking$selectedSections;

    @Unique
    private SectionRenderDispatcher.RenderSection[] chunking$heapSections;

    @Unique
    private double[] chunking$heapScores;

    @Unique
    private static void chunking$siftDownMax(
            double[] scores,
            SectionRenderDispatcher.RenderSection[] sections,
            int size,
            int idx
    ) {
        double s = scores[idx];
        SectionRenderDispatcher.RenderSection sec = sections[idx];
        int half = size >>> 1;
        while (idx < half) {
            int child = (idx << 1) + 1;
            double cScore = scores[child];
            int right = child + 1;
            if (right < size && scores[right] > cScore) {
                child = right;
                cScore = scores[right];
            }
            if (s >= cScore) {
                break;
            }
            scores[idx] = cScore;
            sections[idx] = sections[child];
            idx = child;
        }
        scores[idx] = s;
        sections[idx] = sec;
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void chunking$beginExtractFrame(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        this.chunking$sectionsExtracted = 0;
        this.chunking$extractStartNs = 0L;
        if (camera != null) {
            Vec3 camPos = camera.position();
            if (camPos != null) {
                this.chunking$camX = camPos.x;
                this.chunking$camY = camPos.y;
                this.chunking$camZ = camPos.z;
            }
        }

        FrameGuard gov = FrameGuard.get();
        FrameGuard.Level lvl = gov.level();
        boolean guiTransition = ClientRuntime.isInGuiTransition();
        boolean localSpike = ClientRuntime.isInLocalFrameSpike();
        boolean turningFast = ClientRuntime.get().isTurningFast();
        if (guiTransition) {
            this.chunking$minSections = 2;
            this.chunking$maxSections = 6;
            this.chunking$budgetNs = 350_000L;
        } else if (lvl == FrameGuard.Level.SEVERE || gov.isInitialWorldLoadWindow()) {
            this.chunking$minSections = 4;
            this.chunking$maxSections = 8;
            this.chunking$budgetNs = 450_000L;
        } else if (lvl == FrameGuard.Level.MILD
                || gov.isFramePressured()
                || gov.isInFrameHitch()
                || localSpike) {
            this.chunking$minSections = 4;
            this.chunking$maxSections = 12;
            this.chunking$budgetNs = 650_000L;
        } else {
            int cpus = Runtime.getRuntime().availableProcessors();
            if (turningFast) {
                this.chunking$minSections = 2;
                this.chunking$maxSections = cpus <= 4 ? 10 : (cpus <= 8 ? 16 : 20);
                this.chunking$budgetNs = cpus <= 4 ? 500_000L : 700_000L;
            } else if (ClientRuntime.get().isMovingFast()) {
                this.chunking$minSections = 4;
                this.chunking$maxSections = cpus <= 4 ? 16 : (cpus <= 8 ? 24 : 36);
                this.chunking$budgetNs = cpus <= 4 ? 900_000L : 1_200_000L;
            } else {
                this.chunking$minSections = 4;
                this.chunking$maxSections = cpus <= 4 ? 24 : (cpus <= 8 ? 36 : 48);
                this.chunking$budgetNs = cpus <= 4 ? 1_000_000L : 1_300_000L;
            }
        }
        long floorNs = guiTransition ? 250_000L : (turningFast ? 300_000L : 400_000L);
        long sharedNs = ClientRuntime.remainingStreamingBudgetNs(this.chunking$budgetNs);
        this.chunking$budgetNs = Math.min(this.chunking$budgetNs, Math.max(floorNs, sharedNs));
    }

    @WrapOperation(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"
            ),
            require = 0
    )
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> chunking$selectRadialDirtySections(
            LevelRenderer renderer,
            Operation<ObjectArrayList<SectionRenderDispatcher.RenderSection>> original
    ) {
        ObjectArrayList<SectionRenderDispatcher.RenderSection> all = original.call(renderer);
        SectionUpdateTracker tracker = this.sectionUpdateTracker;
        ClientLevel lvl = this.level;
        if (tracker == null || lvl == null || all == null || all.isEmpty()) {
            return all;
        }

        int cap = this.chunking$maxSections;
        SectionRenderDispatcher disp = renderer.sectionRenderDispatcher();
        int compileQ = disp != null ? disp.getCompileQueueSize() : 0;
        int meshThreads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
        int softQ = Math.max(24, Math.min(128, meshThreads * 5));
        int hardQ = softQ * 2;
        if (compileQ >= hardQ) {
            cap = Math.min(cap, Math.max(4, softQ / 10));
        } else if (compileQ >= softQ) {
            cap = Math.min(cap, Math.max(10, softQ / 5));
        }
        if (this.chunking$heapSections == null || this.chunking$heapSections.length < cap) {
            this.chunking$heapSections = new SectionRenderDispatcher.RenderSection[cap];
            this.chunking$heapScores = new double[cap];
        }
        SectionRenderDispatcher.RenderSection[] hSections = this.chunking$heapSections;
        double[] hScores = this.chunking$heapScores;

        ObjectArrayList<SectionRenderDispatcher.RenderSection> selected = this.chunking$selectedSections;
        if (selected == null) {
            selected = new ObjectArrayList<>(128);
            this.chunking$selectedSections = selected;
        }
        selected.clear();

        double cx = this.chunking$camX;
        double cy = this.chunking$camY;
        double cz = this.chunking$camZ;
        int filled = 0;

        for (int i = 0, n = all.size(); i < n; i++) {
            SectionRenderDispatcher.RenderSection sec = all.get(i);
            long node = sec.getSectionNode();
            SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(node);
            if (dirty == null || !dirty.isDirty()) {
                continue;
            }
            if (dirty.isDirtyFromPlayer()) {
                selected.add(sec);
                continue;
            }

            double dx = (SectionPos.sectionToBlockCoord(SectionPos.x(node)) + 8.0D) - cx;
            double dy = ((SectionPos.sectionToBlockCoord(SectionPos.y(node)) + 8.0D) - cy) * 0.5D;
            double dz = (SectionPos.sectionToBlockCoord(SectionPos.z(node)) + 8.0D) - cz;
            double horizSq = dx * dx + dz * dz;
            double score = horizSq * 16.0D + dy * dy;

            if (filled >= cap && score >= hScores[0]) {
                continue;
            }
            if (sec.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED
                    && !tracker.hasAllNeighbors(lvl, node)) {
                continue;
            }

            if (filled < cap) {
                hSections[filled] = sec;
                hScores[filled] = score;
                filled++;
                if (filled == cap) {
                    for (int k = (filled >>> 1) - 1; k >= 0; k--) {
                        chunking$siftDownMax(hScores, hSections, filled, k);
                    }
                }
            } else {
                hScores[0] = score;
                hSections[0] = sec;
                chunking$siftDownMax(hScores, hSections, filled, 0);
            }
        }

        if (filled > 0) {
            if (filled < cap) {
                for (int k = (filled >>> 1) - 1; k >= 0; k--) {
                    chunking$siftDownMax(hScores, hSections, filled, k);
                }
            }
            for (int end = filled - 1; end > 0; end--) {
                double maxS = hScores[0];
                SectionRenderDispatcher.RenderSection maxSec = hSections[0];
                hScores[0] = hScores[end];
                hSections[0] = hSections[end];
                hScores[end] = maxS;
                hSections[end] = maxSec;
                chunking$siftDownMax(hScores, hSections, end, 0);
            }
            for (int i = 0; i < filled; i++) {
                selected.add(hSections[i]);
            }
            Arrays.fill(hSections, 0, filled, null);
        }
        return selected;
    }

    @Unique
    private long chunking$lastSectionExtractNs;

    @WrapOperation(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;isDirty()Z"
            ),
            require = 0
    )
    private boolean chunking$budgetSectionExtraction(
            SectionUpdateTracker.SectionDirtyState state,
            Operation<Boolean> original
    ) {
        if (!original.call(state)) {
            return false;
        }
        if (state.isDirtyFromPlayer()) {
            return true;
        }
        long now = System.nanoTime();
        if (this.chunking$extractStartNs == 0L) {
            this.chunking$extractStartNs = now;
        }
        this.chunking$lastSectionExtractNs = now;
        if (this.chunking$sectionsExtracted >= this.chunking$minSections
                && (now - this.chunking$extractStartNs) >= this.chunking$budgetNs) {
            return false;
        }
        this.chunking$sectionsExtracted++;
        return true;
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void chunking$clearSelectedSections(CallbackInfo ci) {
        if (this.chunking$extractStartNs != 0L) {
            long spent = Math.max(0L, this.chunking$lastSectionExtractNs - this.chunking$extractStartNs);
            if (spent > 0L) {
                ClientRuntime.consumeStreamingBudget(spent);
            }
            this.chunking$extractStartNs = 0L;
            this.chunking$lastSectionExtractNs = 0L;
        }
        if (this.chunking$selectedSections != null) {
            this.chunking$selectedSections.clear();
        }
    }
}
