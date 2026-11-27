package com.chunking.engine.mixin;

import com.chunking.engine.world.TemplateGrid;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.RandomAccess;

/**
 * Publishes lock-free immutable snapshots of {@code palettes} and {@code entityInfoList} after
 * load/fill, culls {@code placeInWorld} block scans via {@link TemplateGrid}, and trims intermediate
 * {@code BlockPos}/iterator/list allocations inside {@code processBlockInfos}.
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {
    @Shadow
    @Final
    @Mutable
    private List<StructureTemplate.Palette> palettes;

    @Shadow
    @Final
    @Mutable
    private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    // Fresh mutable lists at HEAD allow re-loading/re-filling templates that previously froze snapshots.
    @Inject(method = {"load", "fillFromWorld"}, at = @At("HEAD"))
    private void chunking$prepareMutableLists(CallbackInfo ci) {
        this.palettes = new ArrayList<>();
        this.entityInfoList = new ArrayList<>();
    }

    @Inject(method = {"load", "fillFromWorld"}, at = @At("RETURN"))
    private void chunking$freezeImmutableLists(CallbackInfo ci) {
        this.palettes = List.copyOf(this.palettes);
        this.entityInfoList = List.copyOf(this.entityInfoList);
    }

    @WrapOperation(
            method = "placeInWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;getRandomPalette(Ljava/util/List;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$Palette;"
            )
    )
    private StructureTemplate.Palette chunking$captureSelectedPalette(
            StructurePlaceSettings settings,
            List<StructureTemplate.Palette> paletteList,
            BlockPos pos,
            Operation<StructureTemplate.Palette> original,
            @Share("chunkingPalette") LocalRef<StructureTemplate.Palette> paletteRef
    ) {
        StructureTemplate.Palette chosen = original.call(settings, paletteList, pos);
        paletteRef.set(chosen);
        return chosen;
    }

    // Runs after the empty-template guard so placeInWorld's return value and entity/finalize paths stay exact,
    // and before the three ArrayList(blocks.size()) allocations and processBlockInfos().
    @ModifyVariable(
            method = "placeInWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;getBoundingBox()Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"
            ),
            ordinal = 0
    )
    private List<StructureTemplate.StructureBlockInfo> chunking$cullBlocksBySpatialPalette(
            List<StructureTemplate.StructureBlockInfo> blocks,
            ServerLevelAccessor level,
            BlockPos pos,
            BlockPos pivot,
            StructurePlaceSettings settings,
            RandomSource random,
            int flags,
            @Share("chunkingPalette") LocalRef<StructureTemplate.Palette> paletteRef
    ) {
        if (settings.getBoundingBox() == null
                || blocks.size() < TemplateGrid.MIN_INDEX_BLOCKS) {
            return blocks;
        }
        List<StructureProcessor> processors = settings.getProcessors();
        for (int i = 0, n = processors.size(); i < n; i++) {
            if (processors.get(i).evaluatesEntirePieceState()) {
                return blocks;
            }
        }
        Object palette = paletteRef.get();
        if (palette instanceof TemplateGrid.Access access) {
            return access.chunking$queryBlocks(pos, settings);
        }
        return blocks;
    }

    @WrapMethod(method = "processBlockInfos")
    private static List<StructureTemplate.StructureBlockInfo> chunking$fastProcessBlockInfos(
            ServerLevelAccessor level,
            BlockPos origin,
            BlockPos pivot,
            StructurePlaceSettings settings,
            List<StructureTemplate.StructureBlockInfo> blocks,
            Operation<List<StructureTemplate.StructureBlockInfo>> original
    ) {
        List<StructureProcessor> procList = settings.getProcessors();
        int procCount = procList.size();
        int blockCount = blocks.size();
        if (blockCount == 0 && procCount == 0) {
            return new ArrayList<>(0);
        }

        StructureProcessor[] procs = procCount > 0 ? procList.toArray(new StructureProcessor[procCount]) : null;
        BoundingBox bb = settings.getBoundingBox();
        boolean cullByBB = bb != null;
        if (cullByBB && procs != null) {
            for (int i = 0; i < procCount; i++) {
                if (procs[i].evaluatesEntirePieceState()) {
                    cullByBB = false;
                    break;
                }
            }
        }

        int bbMinX = cullByBB ? bb.minX() : 0;
        int bbMaxX = cullByBB ? bb.maxX() : 0;
        int bbMinY = cullByBB ? bb.minY() : 0;
        int bbMaxY = cullByBB ? bb.maxY() : 0;
        int bbMinZ = cullByBB ? bb.minZ() : 0;
        int bbMaxZ = cullByBB ? bb.maxZ() : 0;

        Mirror mirror = settings.getMirror();
        Rotation rotation = settings.getRotation();
        BlockPos rotPivot = settings.getRotationPivot();
        int px = rotPivot.getX();
        int pz = rotPivot.getZ();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();

        List<StructureTemplate.StructureBlockInfo> processed = new ArrayList<>(blockCount);
        List<StructureTemplate.StructureBlockInfo> rawOut = procs != null ? new ArrayList<>(blockCount) : null;

        boolean randomAccess = blocks instanceof RandomAccess;
        Iterator<StructureTemplate.StructureBlockInfo> it = randomAccess ? null : blocks.iterator();
        for (int idx = 0; idx < blockCount; idx++) {
            StructureTemplate.StructureBlockInfo raw = randomAccess ? blocks.get(idx) : it.next();
            BlockPos rp = raw.pos();
            int lx = rp.getX();
            int ly = rp.getY();
            int lz = rp.getZ();
            if (mirror == Mirror.LEFT_RIGHT) {
                lz = -lz;
            } else if (mirror == Mirror.FRONT_BACK) {
                lx = -lx;
            }
            int rx, rz;
            switch (rotation) {
                case CLOCKWISE_180 -> {
                    rx = px + px - lx;
                    rz = pz + pz - lz;
                }
                case COUNTERCLOCKWISE_90 -> {
                    rx = px - pz + lz;
                    rz = px + pz - lx;
                }
                case CLOCKWISE_90 -> {
                    rx = px + pz - lz;
                    rz = pz - px + lx;
                }
                default -> {
                    rx = lx;
                    rz = lz;
                }
            }
            int wx = rx + ox;
            int wy = ly + oy;
            int wz = rz + oz;
            if (cullByBB && (wx < bbMinX || wx > bbMaxX || wy < bbMinY || wy > bbMaxY || wz < bbMinZ || wz > bbMaxZ)) {
                continue;
            }
            CompoundTag nbt = raw.nbt();
            StructureTemplate.StructureBlockInfo current = new StructureTemplate.StructureBlockInfo(
                    new BlockPos(wx, wy, wz),
                    raw.state(),
                    nbt != null ? nbt.copy() : null
            );
            if (procs != null) {
                for (int i = 0; i < procCount && current != null; i++) {
                    current = procs[i].processBlock(level, origin, pivot, rp, current, settings);
                }
                if (current != null) {
                    processed.add(current);
                    rawOut.add(raw);
                }
            } else {
                processed.add(current);
            }
        }

        if (procs != null) {
            for (int i = 0; i < procCount; i++) {
                processed = procs[i].finalizeProcessing(level, origin, pivot, rawOut, processed, settings);
            }
        }
        return processed;
    }
}
