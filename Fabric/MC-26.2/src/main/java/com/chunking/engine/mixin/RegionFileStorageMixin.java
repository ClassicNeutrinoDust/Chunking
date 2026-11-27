package com.chunking.engine.mixin;

import com.chunking.engine.io.EncodedCache;
import com.chunking.engine.mixin.accessor.RegionFileAccessor;
import com.chunking.engine.access.RawFrameReadAccess;
import com.chunking.engine.access.RegionStorageAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Respects sync-writes flag on RegionFileStorage and adds raw deflate frame read/write fast paths.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements RegionStorageAccess, RawFrameReadAccess {
    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;

    @Shadow
    @Final
    private Path folder;

    @Shadow
    @Final
    @Mutable
    private boolean sync;

    @Shadow
    public abstract void write(ChunkPos pos, CompoundTag nbt) throws IOException;

    @Shadow
    private RegionFile getRegionFile(ChunkPos pos) throws IOException {
        throw new AssertionError();
    }

    @Unique
    private volatile ConcurrentHashMap<Long, Boolean> chunking$regionPreExisted;

    @Unique
    private volatile ConcurrentHashMap<Long, Boolean> chunking$regionOnDisk;

    @Unique
    private ConcurrentHashMap<Long, Boolean> chunking$ensureRegionMap() {
        ConcurrentHashMap<Long, Boolean> map = this.chunking$regionPreExisted;
        if (map == null) {
            synchronized (this) {
                map = this.chunking$regionPreExisted;
                if (map == null) {
                    map = new ConcurrentHashMap<>(64);
                    this.chunking$regionPreExisted = map;
                }
            }
        }
        return map;
    }

    @Unique
    private ConcurrentHashMap<Long, Boolean> chunking$ensureOnDiskMap() {
        ConcurrentHashMap<Long, Boolean> map = this.chunking$regionOnDisk;
        if (map == null) {
            synchronized (this) {
                map = this.chunking$regionOnDisk;
                if (map == null) {
                    map = new ConcurrentHashMap<>(64);
                    this.chunking$regionOnDisk = map;
                }
            }
        }
        return map;
    }

    @Unique
    private boolean chunking$checkRegionPreExisted(int rx, int rz) {
        ConcurrentHashMap<Long, Boolean> map = chunking$ensureRegionMap();
        Long key = Long.valueOf(ChunkPos.pack(rx, rz));
        Boolean cached = map.get(key);
        if (cached != null) {
            return cached.booleanValue();
        }
        Path dir = this.folder;
        boolean exists = dir != null && Files.exists(dir.resolve("r." + rx + "." + rz + ".mca"));
        Boolean prev = map.putIfAbsent(key, Boolean.valueOf(exists));
        boolean finalExists = prev != null ? prev.booleanValue() : exists;
        chunking$ensureOnDiskMap().putIfAbsent(key, Boolean.valueOf(finalExists));
        return finalExists;
    }

    @Unique
    private boolean chunking$isRegionOpenOrOnDisk(int rx, int rz) {
        long packed = ChunkPos.pack(rx, rz);
        synchronized (this.regionCache) {
            if (this.regionCache.containsKey(packed)) {
                return true;
            }
        }
        return chunking$hasRegionFileOnDisk(rx, rz);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$disableSyncDiskWritesByDefault(CallbackInfo ci) {
        this.sync = Boolean.getBoolean("chunking.sync_disk_writes");
        chunking$ensureRegionMap();
        chunking$ensureOnDiskMap();
    }

    @Inject(method = "getRegionFile", at = @At("HEAD"))
    private void chunking$recordRegionExistenceBeforeOpen(ChunkPos pos, CallbackInfoReturnable<RegionFile> cir) {
        chunking$checkRegionPreExisted(pos.getRegionX(), pos.getRegionZ());
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void chunking$skipReadIfNoRegionFile(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) {
        if (!chunking$isRegionOpenOrOnDisk(pos.getRegionX(), pos.getRegionZ())) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void chunking$skipScanIfNoRegionFile(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) {
        if (!chunking$isRegionOpenOrOnDisk(pos.getRegionX(), pos.getRegionZ())) {
            ci.cancel();
        }
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void chunking$fastWritePreEncoded(ChunkPos pos, CompoundTag nbt, CallbackInfo ci) throws IOException {
        if (nbt == null) {
            return;
        }
        if (SharedConstants.DEBUG_DONT_SAVE_WORLD) {
            return;
        }
        chunking$ensureOnDiskMap().put(Long.valueOf(ChunkPos.pack(pos.getRegionX(), pos.getRegionZ())), Boolean.TRUE);
        RegionFile regionFile = this.getRegionFile(pos);
        boolean isDeflate = ((RegionFileAccessor) regionFile).chunking$getVersion() == RegionFileVersion.VERSION_DEFLATE;
        if (isDeflate && nbt.size() == 1 && nbt.contains(EncodedCache.PRE_ENCODED_KEY)) {
            byte[] preEncoded = EncodedCache.extractPreEncodedFrame(nbt);
            if (preEncoded != null) {
                ((RegionFileAccessor) regionFile).chunking$invokeWrite(pos, ByteBuffer.wrap(preEncoded));
                ci.cancel();
                return;
            }
        }
        if (nbt.contains(EncodedCache.PRE_ENCODED_KEY)) {
            CompoundTag unwrapped = EncodedCache.unwrapIfPreEncoded(nbt);
            this.write(pos, unwrapped);
            ci.cancel();
            return;
        }
        if (isDeflate) {
            byte[] encoded = EncodedCache.extractOrEncodeFrame(nbt);
            if (encoded != null) {
                ((RegionFileAccessor) regionFile).chunking$invokeWrite(pos, ByteBuffer.wrap(encoded));
                ci.cancel();
            }
        }
    }

    @Override
    public byte[] chunking$readRawDeflateFrame(ChunkPos pos) throws IOException {
        if (!chunking$isRegionOpenOrOnDisk(pos.getRegionX(), pos.getRegionZ())) {
            return RawFrameReadAccess.EMPTY_ABSENT_FRAME;
        }
        RegionFile regionFile = this.getRegionFile(pos);
        if ((Object) regionFile instanceof RawFrameReadAccess access) {
            return access.chunking$readRawDeflateFrame(pos);
        }
        return null;
    }

    @Override
    public boolean chunking$hasPreExistingRegionFile(int regionX, int regionZ) {
        return chunking$checkRegionPreExisted(regionX, regionZ);
    }

    @Override
    public boolean chunking$hasRegionFileOnDisk(int regionX, int regionZ) {
        ConcurrentHashMap<Long, Boolean> map = chunking$ensureOnDiskMap();
        Long key = Long.valueOf(ChunkPos.pack(regionX, regionZ));
        Boolean cached = map.get(key);
        if (cached != null) {
            return cached.booleanValue();
        }
        return chunking$checkRegionPreExisted(regionX, regionZ);
    }
}
