package com.chunking.engine.mixin;

import com.chunking.engine.mixin.accessor.SkyDataLayerStorageMapAccessor;
import com.chunking.engine.access.LightVisibleLockAccess;
import com.chunking.engine.world.LightMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.StampedLock;

/**
 * Coordinates sky column top-section tracking and lock-free {@link StampedLock} optimistic reads
 * over visible sky light snapshots with {@link LayerLightSectionStorageMixin}.
 */
@Mixin(SkyLightSectionStorage.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class SkyLightSectionStorageMixin extends LayerLightSectionStorage {
    protected SkyLightSectionStorageMixin(
            net.minecraft.world.level.LightLayer layer,
            LightChunkGetter chunkSource,
            DataLayerStorageMap sectionData
    ) {
        super(layer, chunkSource, sectionData);
    }

    @Inject(method = "onNodeAdded", at = @At("HEAD"))
    private void chunking$trackColumnOnAdd(long sectionPos, CallbackInfo ci) {
        ((LightVisibleLockAccess) this).chunking$recordDirtySkyColumn(SectionPos.getZeroNode(sectionPos));
    }

    @Inject(method = "onNodeRemoved", at = @At("HEAD"))
    private void chunking$trackColumnOnRemove(long sectionPos, CallbackInfo ci) {
        ((LightVisibleLockAccess) this).chunking$recordDirtySkyColumn(SectionPos.getZeroNode(sectionPos));
    }

    /**
     * @author Chunking
     * @reason Lock-free optimistic StampedLock read over visible sky light tables without @WrapMethod lambda overhead or CAS contention.
     */
    @Overwrite
    protected int getLightValue(long blockPos, boolean cached) {
        long secPos = SectionPos.blockToSection(blockPos);
        int sectionY = SectionPos.y(secPos);
        if (cached) {
            DataLayerStorageMap upd = this.updatingSectionData;
            SkyDataLayerStorageMapAccessor skyAcc = (SkyDataLayerStorageMapAccessor) (Object) upd;
            int topY = skyAcc.chunking$getTopSections().get(SectionPos.getZeroNode(secPos));
            if (topY == skyAcc.chunking$getCurrentLowestY() || sectionY >= topY) {
                return this.lightOnInSection(secPos) ? 15 : 0;
            }
            DataLayer dl = this.getDataLayer(upd, secPos);
            if (dl == null) {
                blockPos = BlockPos.getFlatIndex(blockPos);
                while (dl == null) {
                    sectionY++;
                    if (sectionY >= topY) {
                        return 15;
                    }
                    secPos = SectionPos.offset(secPos, Direction.UP);
                    dl = this.getDataLayer(upd, secPos);
                }
            }
            return dl.get(
                    SectionPos.sectionRelative(BlockPos.getX(blockPos)),
                    SectionPos.sectionRelative(BlockPos.getY(blockPos)),
                    SectionPos.sectionRelative(BlockPos.getZ(blockPos))
            );
        }

        LightVisibleLockAccess vis = (LightVisibleLockAccess) this;
        StampedLock sl = vis.chunking$visibleLock();
        long stamp = sl.tryOptimisticRead();
        int val = -1;
        if (stamp != 0L) {
            try {
                val = chunking$readVisibleSkyLight(vis, sl, stamp, blockPos, secPos, sectionY);
            } catch (ArrayIndexOutOfBoundsException | NullPointerException torn) {
                if (sl.validate(stamp)) {
                    throw torn;
                }
                val = -1;
            }
        }
        if (val < 0 || !sl.validate(stamp)) {
            long rs = sl.readLock();
            try {
                val = chunking$readVisibleSkyLight(vis, null, 0L, blockPos, secPos, sectionY);
                if (val < 0) {
                    val = 15;
                }
            } finally {
                sl.unlockRead(rs);
            }
        }
        return val;
    }

    @Unique
    private static int chunking$readVisibleSkyLight(
            LightVisibleLockAccess vis,
            StampedLock sl,
            long stamp,
            long blockPos,
            long secPos,
            int sectionY
    ) {
        LightMaps.IntTable topTable = vis.chunking$getVisTopTable();
        int lowestY = vis.chunking$getVisLowestY();
        int topY = topTable.getOptimistic(SectionPos.getZeroNode(secPos));
        if (topY == lowestY || topY == Integer.MAX_VALUE || sectionY >= topY) {
            return 15;
        }
        LightMaps.ObjTable objTable = vis.chunking$getVisObjTable();
        DataLayer dl = objTable.getOptimistic(secPos);
        if (dl == null) {
            blockPos = BlockPos.getFlatIndex(blockPos);
            int guard = 0;
            while (dl == null) {
                sectionY++;
                if (sectionY >= topY) {
                    return 15;
                }
                if (sl != null && ++guard > 512) {
                    return -1;
                }
                secPos = SectionPos.offset(secPos, Direction.UP);
                dl = objTable.getOptimistic(secPos);
            }
        }
        VarHandle.loadLoadFence();
        if (sl != null && !sl.validate(stamp)) {
            return -1;
        }
        return dl.get(
                SectionPos.sectionRelative(BlockPos.getX(blockPos)),
                SectionPos.sectionRelative(BlockPos.getY(blockPos)),
                SectionPos.sectionRelative(BlockPos.getZ(blockPos))
        );
    }
}
