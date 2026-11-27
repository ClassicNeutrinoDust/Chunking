package com.chunking.engine.io;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Cleaner;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Offloads NBT binary encoding and single-shot RFC-1950 zlib compression/decompression
 * ({@link Deflater#BEST_SPEED} / reused {@link Inflater}) onto parallel serializer/parser threads
 * without per-chunk {@code DeflaterOutputStream} / {@code InflaterInputStream} stream allocations
 * or per-call locking, and wraps the ~5 KB compressed frame inside a single-entry carrier {@link CompoundTag}
 * so the ~300 KB multi-node {@link CompoundTag} tree dies immediately in young-gen Eden instead of
 * accumulating in {@code IOWorker.pendingWrites}.
 */
public final class EncodedCache {
    public static final String PRE_ENCODED_KEY = "__chunking_frame__";
    private static final boolean COMPACT_HEAP = Runtime.getRuntime().maxMemory() <= (4608L * 1024L * 1024L);
    private static final int MAX_SCRATCH_BYTES = COMPACT_HEAP ? (128 * 1024) : (256 * 1024);
    private static final int MAX_COMPRESSED_SCRATCH_BYTES = 64 * 1024;
    private static final int MAX_DECODE_SCRATCH_BYTES = COMPACT_HEAP ? (256 * 1024) : (512 * 1024);

    private static final Cleaner CLEANER = Cleaner.create();
    private static final AtomicLong GENERATION = new AtomicLong();

    public static final class FastByteOut extends OutputStream {
        private byte[] buf;
        private int count;

        public FastByteOut(int initialCapacity) {
            this.buf = new byte[Math.max(256, initialCapacity)];
        }

        public void resetEmpty(int shrinkTo) {
            if (this.buf.length > MAX_SCRATCH_BYTES) {
                this.buf = new byte[shrinkTo];
            }
            this.count = 0;
        }

        public void resetEmptyCap(int shrinkTo, int maxRetain) {
            if (this.buf.length > maxRetain) {
                this.buf = new byte[shrinkTo];
            }
            this.count = 0;
        }

        public byte[] rawBuf() {
            return this.buf;
        }

        public int size() {
            return this.count;
        }

        public void resetForFrame(int versionId) {
            if (this.buf.length > MAX_COMPRESSED_SCRATCH_BYTES) {
                this.buf = new byte[16384];
            }
            this.buf[0] = 0;
            this.buf[1] = 0;
            this.buf[2] = 0;
            this.buf[3] = 0;
            this.buf[4] = (byte) versionId;
            this.count = 5;
        }

        public void patchFrameLength() {
            int payloadLen = (this.count - 5) + 1;
            this.buf[0] = (byte) (payloadLen >>> 24);
            this.buf[1] = (byte) (payloadLen >>> 16);
            this.buf[2] = (byte) (payloadLen >>> 8);
            this.buf[3] = (byte) payloadLen;
        }

        public byte[] toByteArray() {
            return Arrays.copyOf(this.buf, this.count);
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > this.buf.length) {
                int newCap = Math.max(minCapacity, this.buf.length << 1);
                this.buf = Arrays.copyOf(this.buf, newCap);
            }
        }

        @Override
        public void write(int b) {
            ensureCapacity(this.count + 1);
            this.buf[this.count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (len <= 0) {
                return;
            }
            ensureCapacity(this.count + len);
            System.arraycopy(b, off, this.buf, this.count, len);
            this.count += len;
        }
    }

    private static final class ResettableByteIn extends InputStream {
        private static final byte[] EMPTY = new byte[0];
        private byte[] buf = EMPTY;
        private int pos;
        private int end;

        void reset(byte[] buf, int off, int len) {
            this.buf = buf;
            this.pos = off;
            this.end = off + len;
        }

        void clearRef() {
            this.buf = EMPTY;
            this.pos = 0;
            this.end = 0;
        }

        @Override
        public int read() {
            return this.pos < this.end ? (this.buf[this.pos++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int rem = this.end - this.pos;
            if (rem <= 0) {
                return -1;
            }
            int toCopy = Math.min(len, rem);
            System.arraycopy(this.buf, this.pos, b, off, toCopy);
            this.pos += toCopy;
            return toCopy;
        }
    }

    private static final class DeflaterCloser implements Runnable {
        private final Deflater deflater;

        DeflaterCloser(Deflater deflater) {
            this.deflater = deflater;
        }

        @Override
        public void run() {
            this.deflater.end();
        }
    }

    private static final class InflaterCloser implements Runnable {
        private final Inflater inflater;

        InflaterCloser(Inflater inflater) {
            this.inflater = inflater;
        }

        @Override
        public void run() {
            this.inflater.end();
        }
    }

    private static final class EncoderScratch {
        final long generation;
        final FastByteOut rawNbt = new FastByteOut(32768);
        final DataOutputStream rawDos = new DataOutputStream(rawNbt);
        final FastByteOut out = new FastByteOut(16384);
        final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        final byte[] deflateBuf = new byte[8192];
        final Cleaner.Cleanable cleanable;

        EncoderScratch(long generation) {
            this.generation = generation;
            this.cleanable = CLEANER.register(this, new DeflaterCloser(this.deflater));
        }

        void close() {
            this.cleanable.clean();
        }
    }

    private static final class DecoderScratch {
        final long generation;
        final Inflater inflater = new Inflater();
        final byte[] inflateBuf = new byte[32768];
        final FastByteOut rawNbt = new FastByteOut(65536);
        final ResettableByteIn byteIn = new ResettableByteIn();
        final DataInputStream dis = new DataInputStream(byteIn);
        final Cleaner.Cleanable cleanable;

        DecoderScratch(long generation) {
            this.generation = generation;
            this.cleanable = CLEANER.register(this, new InflaterCloser(this.inflater));
        }

        void close() {
            this.cleanable.clean();
        }
    }

    private static final ThreadLocal<EncoderScratch> SCRATCH = new ThreadLocal<>();
    private static final ThreadLocal<DecoderScratch> DECODE_SCRATCH = new ThreadLocal<>();

    private EncodedCache() {}

    private static EncoderScratch acquireEncoderScratch() {
        long gen = GENERATION.get();
        EncoderScratch s = SCRATCH.get();
        if (s == null || s.generation != gen) {
            if (s != null) {
                s.close();
            }
            s = new EncoderScratch(gen);
            SCRATCH.set(s);
        }
        return s;
    }

    private static DecoderScratch acquireDecoderScratch() {
        long gen = GENERATION.get();
        DecoderScratch s = DECODE_SCRATCH.get();
        if (s == null || s.generation != gen) {
            if (s != null) {
                s.close();
            }
            s = new DecoderScratch(gen);
            DECODE_SCRATCH.set(s);
        }
        return s;
    }

    public static final java.util.concurrent.ConcurrentHashMap<java.nio.file.Path, Boolean> FOLDER_HAS_MCC =
            new java.util.concurrent.ConcurrentHashMap<>(16);

    public static byte[] encodeDeflateFrame(CompoundTag tag) {
        if (tag == null || RegionFileVersion.getSelected() != RegionFileVersion.VERSION_DEFLATE) {
            return null;
        }
        if (tag.get(PRE_ENCODED_KEY) instanceof ByteArrayTag bat) {
            if (tag.size() == 1) {
                return bat.getAsByteArray();
            }
            tag = unwrapIfPreEncoded(tag);
        }
        EncoderScratch s = acquireEncoderScratch();
        s.rawNbt.resetEmpty(32768);
        try {
            NbtIo.write(tag, s.rawDos);
        } catch (IOException ignored) {
            return null;
        }
        s.out.resetForFrame(RegionFileVersion.VERSION_DEFLATE.getId());
        s.deflater.reset();
        try {
            s.deflater.setInput(s.rawNbt.rawBuf(), 0, s.rawNbt.size());
            s.deflater.finish();
            while (!s.deflater.finished()) {
                int n = s.deflater.deflate(s.deflateBuf);
                if (n > 0) {
                    s.out.write(s.deflateBuf, 0, n);
                } else if (s.deflater.needsInput()) {
                    return null;
                }
            }
            s.out.patchFrameLength();
            return s.out.toByteArray();
        } finally {
            s.deflater.reset();
        }
    }

    public static CompoundTag decodeDeflateFrame(byte[] frame) {
        if (frame == null || frame.length <= 5 || frame[4] != (byte) RegionFileVersion.VERSION_DEFLATE.getId()) {
            return null;
        }
        DecoderScratch s = acquireDecoderScratch();
        s.rawNbt.resetEmptyCap(65536, MAX_DECODE_SCRATCH_BYTES);
        s.inflater.reset();
        s.inflater.setInput(frame, 5, frame.length - 5);
        try {
            while (!s.inflater.finished()) {
                int n = s.inflater.inflate(s.inflateBuf);
                if (n > 0) {
                    s.rawNbt.write(s.inflateBuf, 0, n);
                } else if (s.inflater.needsInput() || s.inflater.needsDictionary()) {
                    return null;
                }
            }
            s.byteIn.reset(s.rawNbt.rawBuf(), 0, s.rawNbt.size());
            return NbtIo.read(s.dis);
        } catch (DataFormatException | IOException ignored) {
            return null;
        } finally {
            s.byteIn.clearRef();
            s.inflater.reset();
        }
    }

    public static CompoundTag wrapPreEncodedFrame(byte[] frame) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put(PRE_ENCODED_KEY, new ByteArrayTag(frame));
        return wrapper;
    }

    public static byte[] extractPreEncodedFrame(CompoundTag nbt) {
        if (nbt != null && nbt.size() == 1 && nbt.get(PRE_ENCODED_KEY) instanceof ByteArrayTag bat) {
            return bat.getAsByteArray();
        }
        return null;
    }

    public static byte[] extractOrEncodeFrame(CompoundTag nbt) {
        if (nbt == null) {
            return null;
        }
        if (nbt.size() == 1 && nbt.get(PRE_ENCODED_KEY) instanceof ByteArrayTag bat) {
            return bat.getAsByteArray();
        }
        if (!nbt.isEmpty()) {
            return encodeDeflateFrame(nbt);
        }
        return null;
    }

    public static CompoundTag unwrapIfPreEncoded(CompoundTag nbt) {
        if (nbt != null && nbt.get(PRE_ENCODED_KEY) instanceof ByteArrayTag bat) {
            CompoundTag decoded = decodeDeflateFrame(bat.getAsByteArray());
            if (decoded == null) {
                throw new IllegalStateException("Failed to decode pre-encoded chunking frame");
            }
            if (nbt.size() > 1) {
                for (String key : nbt.keySet()) {
                    if (!PRE_ENCODED_KEY.equals(key)) {
                        net.minecraft.nbt.Tag extra = nbt.get(key);
                        if (extra != null) {
                            decoded.put(key, extra);
                        }
                    }
                }
            }
            return decoded;
        }
        return nbt;
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        FOLDER_HAS_MCC.clear();
        EncoderScratch enc = SCRATCH.get();
        if (enc != null) {
            enc.close();
            SCRATCH.remove();
        }
        DecoderScratch dec = DECODE_SCRATCH.get();
        if (dec != null) {
            dec.close();
            DECODE_SCRATCH.remove();
        }
    }
}
