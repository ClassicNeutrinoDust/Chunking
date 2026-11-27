package com.chunking.engine.mixin;

import com.chunking.engine.io.EncodedCache;
import com.chunking.engine.access.RawFrameReadAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Eliminates thousands of per-chunk {@code Files.deleteIfExists(chunk.mcc) -> WindowsException}
 * filesystem syscalls inside {@link RegionFile} when the region folder has never stored any
 * oversized external {@code .mcc} chunks, and exposes {@link #chunking$readRawDeflateFrame(ChunkPos)}
 * to bypass stream wrapping during offloaded chunk loads using a reusable thread-local scratch buffer.
 */
@Mixin(RegionFile.class)
public abstract class RegionFileMixin implements RawFrameReadAccess {
    @Unique
    private static final int CHUNKING$MAX_RETAINED_READ_BUF = 256 * 1024;

    @Unique
    private static final ThreadLocal<ByteBuffer> CHUNKING$READ_BUF = new ThreadLocal<>();

    @Shadow
    @Final
    private FileChannel file;

    @Shadow
    @Final
    private IntBuffer offsets;

    @Shadow
    private static int getOffsetIndex(ChunkPos pos) {
        throw new AssertionError();
    }

    @Unique
    private boolean chunking$mayHaveExternalMcc;

    @Unique
    private Path chunking$externalFileDir;

    @Inject(
            method = "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;Z)V",
            at = @At("RETURN")
    )
    private void chunking$scanExternalDirOnce(
            RegionStorageInfo info,
            Path path,
            Path externalFileDir,
            RegionFileVersion version,
            boolean sync,
            CallbackInfo ci
    ) {
        this.chunking$externalFileDir = externalFileDir;
        if (externalFileDir == null) {
            this.chunking$mayHaveExternalMcc = false;
            return;
        }
        Boolean cached = EncodedCache.FOLDER_HAS_MCC.get(externalFileDir);
        if (cached == null) {
            if (EncodedCache.FOLDER_HAS_MCC.size() > 64) {
                EncodedCache.FOLDER_HAS_MCC.clear();
            }
            boolean found = false;
            if (Files.isDirectory(externalFileDir)) {
                try (Stream<Path> list = Files.list(externalFileDir)) {
                    found = list.anyMatch(p -> p.getFileName().toString().endsWith(".mcc"));
                } catch (Throwable ignored) {
                    found = true;
                }
            }
            Boolean prev = EncodedCache.FOLDER_HAS_MCC.putIfAbsent(externalFileDir, Boolean.valueOf(found));
            cached = prev != null ? prev : Boolean.valueOf(found);
        }
        this.chunking$mayHaveExternalMcc = cached.booleanValue();
    }

    @Inject(method = "writeToExternalFile", at = @At("HEAD"))
    private void chunking$markExternalMccPresent(CallbackInfoReturnable<?> cir) {
        this.chunking$mayHaveExternalMcc = true;
        Path dir = this.chunking$externalFileDir;
        if (dir != null) {
            EncodedCache.FOLDER_HAS_MCC.put(dir, Boolean.TRUE);
        }
    }

    @Dynamic
    @Inject(method = "lambda$write$0", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunking$skipMissingExternalMccDelete(CallbackInfo ci) {
        if (!this.chunking$mayHaveExternalMcc) {
            ci.cancel();
        }
    }

    @Override
    public synchronized byte[] chunking$readRawDeflateFrame(ChunkPos pos) throws IOException {
        int offset = this.offsets.get(getOffsetIndex(pos));
        if (offset == 0) {
            return RawFrameReadAccess.EMPTY_ABSENT_FRAME;
        }
        int sectorNumber = (offset >>> 8) & 0xFFFFFF;
        int numSectors = offset & 0xFF;
        int maxBytes = numSectors * 4096;
        if (sectorNumber < 2 || maxBytes < 5) {
            return null;
        }
        ByteBuffer buf;
        if (maxBytes <= CHUNKING$MAX_RETAINED_READ_BUF) {
            buf = CHUNKING$READ_BUF.get();
            if (buf == null || buf.capacity() < maxBytes) {
                int allocCap = Math.min(
                        CHUNKING$MAX_RETAINED_READ_BUF,
                        Math.max(16384, Integer.highestOneBit(maxBytes - 1) << 1)
                );
                buf = ByteBuffer.allocate(Math.max(maxBytes, allocCap));
                CHUNKING$READ_BUF.set(buf);
            }
            buf.clear();
            buf.limit(maxBytes);
        } else {
            buf = ByteBuffer.allocate(maxBytes);
        }
        long fileOffset = (long) sectorNumber * 4096L;
        while (buf.hasRemaining()) {
            int read = this.file.read(buf, fileOffset);
            if (read < 0) {
                break;
            }
            fileOffset += read;
        }
        buf.flip();
        if (buf.remaining() < 5) {
            return null;
        }
        int declaredLen = buf.getInt();
        byte versionByte = buf.get();
        int payloadLen = declaredLen - 1;
        if (declaredLen <= 1
                || (versionByte & 128) != 0
                || versionByte != (byte) RegionFileVersion.VERSION_DEFLATE.getId()
                || payloadLen > buf.remaining()) {
            return null;
        }
        byte[] frame = new byte[5 + payloadLen];
        frame[0] = (byte) (declaredLen >>> 24);
        frame[1] = (byte) (declaredLen >>> 16);
        frame[2] = (byte) (declaredLen >>> 8);
        frame[3] = (byte) declaredLen;
        frame[4] = versionByte;
        buf.get(frame, 5, payloadLen);
        return frame;
    }
}
