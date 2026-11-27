package com.chunking.engine.simd;

import com.chunking.engine.ChunkingMod;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.chunking.engine.world.FlatBiomeTree;
import com.chunking.engine.access.ImprovedNoiseAccess;

/**
 * Optional Java 25 Panama FFM bridge to the bundled native SIMD library
 * ({@code chunking_avx2.dll} on Windows x64, {@code libchunking_avx2.so} on Linux x64,
 * {@code libchunking_avx2.dylib} on macOS x64, and {@code libchunking_neon.so/.dylib} on ARM64)
 * for bit-exact {@code NormalNoise}, {@code BlendedNoise}, and {@code FlatRTree} evaluation
 * using {@link Linker.Option#critical(boolean)}.
 */
public final class NativeAvx2 {
    public static final long OCTAVE_STRIDE = 576L;
    private static final int PERM_OFFSET = 48;

    public static final long BLENDED_OCTAVE_STRIDE = 576L;
    private static final int BLENDED_PERM_OFFSET = 64;

    public static final long RTREE_NODE_STRIDE = 64L;

    private static final boolean AVAILABLE;
    private static final MethodHandle MH_NORMAL_COL;
    private static final MethodHandle MH_NORMAL_BATCH3D_INT;
    private static final MethodHandle MH_BLENDED_COL;
    private static final MethodHandle MH_RTREE_COL;

    private static final class RTreeColumnScratch {
        MemorySegment dTargets;
        MemorySegment outLeaves;
        int capacity;
        MemorySegment bandMins;
        MemorySegment bandMaxs;
        int bandCapacity;

        void ensureCapacity(int count) {
            if (count > capacity) {
                int newCap = Math.max(count, Math.max(256, capacity * 2));
                Arena arena = Arena.ofAuto();
                dTargets = arena.allocate((long) newCap * 4L, 32L);
                outLeaves = arena.allocate((long) newCap * 4L, 32L);
                capacity = newCap;
            }
        }

        void ensureBandCapacity(int bands) {
            if (bands > bandCapacity) {
                int newCap = Math.max(bands, Math.max(16, bandCapacity * 2));
                Arena arena = Arena.ofAuto();
                bandMins = arena.allocate((long) newCap * 8L, 32L);
                bandMaxs = arena.allocate((long) newCap * 8L, 32L);
                bandCapacity = newCap;
            }
        }
    }

    private static final ThreadLocal<RTreeColumnScratch> RTREE_SCRATCH_TL =
            ThreadLocal.withInitial(RTreeColumnScratch::new);

    static {
        boolean ok = false;
        MethodHandle mhCol = null;
        MethodHandle mhBatch = null;
        MethodHandle mhBlended = null;
        MethodHandle mhRTree = null;
        String loadedPath = null;
        String reason = null;
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        NativeTarget target = resolveNativeTarget(os, arch);
        if (target != null) {
            try {
                Path dllPath = extractBundledNative(target);
                if (dllPath != null && Files.isRegularFile(dllPath)) {
                    SymbolLookup lookup = SymbolLookup.libraryLookup(dllPath, Arena.global());
                    Linker linker = Linker.nativeLinker();
                    Linker.Option criticalHeap = Linker.Option.critical(true);

                    // Guard against non-AVX2 x86-64 CPUs (Pentium/Celeron/Sandy/Ivy Bridge/AMD FX)
                    boolean cpuSupportsAvx2 = true;
                    MemorySegment cpuCheckSym = lookup.find("chunking_avx2_cpu_supported").orElse(null);
                    if (cpuCheckSym != null) {
                        MethodHandle mhCpu = linker.downcallHandle(
                                cpuCheckSym,
                                FunctionDescriptor.of(ValueLayout.JAVA_INT),
                                criticalHeap
                        );
                        cpuSupportsAvx2 = ((int) mhCpu.invokeExact()) != 0;
                    }
                    if (!cpuSupportsAvx2) {
                        reason = "CPU lacks AVX2 hardware support (falling back to pure Java)";
                    } else {
                    mhCol = linker.downcallHandle(
                            lookup.findOrThrow("chunking_avx2_normal_col"),
                            FunctionDescriptor.ofVoid(
                                    ValueLayout.ADDRESS,      // const OctaveDesc* octs
                                    ValueLayout.JAVA_INT,     // int32_t numOctaves
                                    ValueLayout.JAVA_DOUBLE,  // double xFixed
                                    ValueLayout.ADDRESS,      // const int32_t* by
                                    ValueLayout.JAVA_DOUBLE,  // double yScale
                                    ValueLayout.JAVA_DOUBLE,  // double zFixed
                                    ValueLayout.ADDRESS,      // double* out
                                    ValueLayout.JAVA_INT      // int32_t count
                            ),
                            criticalHeap
                    );

                    mhBatch = linker.downcallHandle(
                            lookup.findOrThrow("chunking_avx2_normal_batch3d_int"),
                            FunctionDescriptor.ofVoid(
                                    ValueLayout.ADDRESS,      // const OctaveDesc* octs
                                    ValueLayout.JAVA_INT,     // int32_t numOctaves
                                    ValueLayout.ADDRESS,      // const int32_t* bx
                                    ValueLayout.ADDRESS,      // const int32_t* by
                                    ValueLayout.ADDRESS,      // const int32_t* bz
                                    ValueLayout.JAVA_DOUBLE,  // double xzScale
                                    ValueLayout.JAVA_DOUBLE,  // double yScale
                                    ValueLayout.ADDRESS,      // double* out
                                    ValueLayout.JAVA_INT      // int32_t count
                            ),
                            criticalHeap
                    );

                    // Optional bindings for backward compatibility with older chunking_avx2.dll builds
                    MemorySegment blendedSym = lookup.find("chunking_avx2_blended_col").orElse(null);
                    if (blendedSym != null) {
                        mhBlended = linker.downcallHandle(
                                blendedSym,
                                FunctionDescriptor.ofVoid(
                                        ValueLayout.ADDRESS,      // const BlendedOctaveDesc* octs
                                        ValueLayout.JAVA_INT,     // int32_t numMain
                                        ValueLayout.JAVA_INT,     // int32_t numMin
                                        ValueLayout.JAVA_INT,     // int32_t numMax
                                        ValueLayout.JAVA_DOUBLE,  // double dx
                                        ValueLayout.JAVA_DOUBLE,  // double dz
                                        ValueLayout.JAVA_DOUBLE,  // double fx
                                        ValueLayout.JAVA_DOUBLE,  // double fz
                                        ValueLayout.ADDRESS,      // const int32_t* by
                                        ValueLayout.JAVA_INT,     // int32_t startIdx
                                        ValueLayout.ADDRESS,      // double* out
                                        ValueLayout.JAVA_INT      // int32_t totalCount
                                ),
                                criticalHeap
                        );
                    }

                    MemorySegment rtreeSym = lookup.find("chunking_avx2_rtree_search_column").orElse(null);
                    if (rtreeSym != null) {
                        mhRTree = linker.downcallHandle(
                                rtreeSym,
                                FunctionDescriptor.of(
                                        ValueLayout.JAVA_INT,     // return int32_t lastLeafIdx
                                        ValueLayout.ADDRESS,      // const FlatRTreeNode* nodes
                                        ValueLayout.JAVA_INT,     // int32_t nodeCount
                                        ValueLayout.JAVA_INT,     // int32_t seedLeafIdx
                                        ValueLayout.JAVA_INT,     // int32_t allDepthNonNeg
                                        ValueLayout.JAVA_INT,     // int32_t disjointDepthBands
                                        ValueLayout.ADDRESS,      // const int64_t* depthBandMins
                                        ValueLayout.ADDRESS,      // const int64_t* depthBandMaxs
                                        ValueLayout.JAVA_INT,     // int32_t bandCount
                                        ValueLayout.JAVA_INT,     // int32_t t0
                                        ValueLayout.JAVA_INT,     // int32_t t1
                                        ValueLayout.JAVA_INT,     // int32_t t2
                                        ValueLayout.JAVA_INT,     // int32_t t3
                                        ValueLayout.JAVA_INT,     // int32_t t5
                                        ValueLayout.ADDRESS,      // const int32_t* dTargets
                                        ValueLayout.ADDRESS,      // int32_t* outLeafIdx
                                        ValueLayout.JAVA_INT      // int32_t count
                                ),
                                criticalHeap
                        );
                    }

                    loadedPath = dllPath.toString();
                    ok = true;
                    }
                } else {
                    reason = target.fileName() + " not found in resources/natives/" + target.platformDir() + " or workspace";
                }
            } catch (Throwable t) {
                reason = t.toString();
                ChunkingMod.LOGGER.warn("Chunking AVX2 native library unavailable, using Java fallback: {}", reason);
            }
        } else {
            reason = "unsupported platform (" + os + "/" + arch + ")";
        }
        AVAILABLE = ok;
        MH_NORMAL_COL = mhCol;
        MH_NORMAL_BATCH3D_INT = mhBatch;
        MH_BLENDED_COL = mhBlended;
        MH_RTREE_COL = mhRTree;

        if (ok) {
            ChunkingMod.LOGGER.info(
                    "Chunking native SIMD initialized: available={}, platform={}, blendedKernel={}, rtreeKernel={}, lib={}",
                    true, target.platformDir(), mhBlended != null, mhRTree != null, loadedPath
            );
        } else {
            ChunkingMod.LOGGER.info(
                    "Chunking native SIMD initialized: available={}, blendedKernel={}, rtreeKernel={}, reason={}",
                    false, false, false, reason
            );
        }
    }

    private NativeAvx2() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean hasBlendedKernel() {
        return MH_BLENDED_COL != null;
    }

    public static boolean hasRTreeKernel() {
        return MH_RTREE_COL != null;
    }

    private record NativeTarget(String platformDir, String legacyPlatformDir, String fileName) {}

    private static NativeTarget resolveNativeTarget(String os, String arch) {
        boolean isX64 = arch.contains("amd64") || arch.contains("x86_64");
        boolean isArm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return isX64 ? new NativeTarget("windows-x64", null, "chunking_avx2.dll") : null;
        }
        if (os.contains("linux")) {
            if (isX64) {
                return new NativeTarget("linux-x64", null, "libchunking_avx2.so");
            }
            if (isArm64) {
                return new NativeTarget("linux-aarch64", "linux-arm64", "libchunking_neon.so");
            }
            return null;
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("os x")) {
            if (isX64) {
                return new NativeTarget("macos-x64", null, "libchunking_avx2.dylib");
            }
            if (isArm64) {
                return new NativeTarget("macos-aarch64", "macos-arm64", "libchunking_neon.dylib");
            }
            return null;
        }
        return null;
    }

    private static Path extractBundledNative(NativeTarget target) throws IOException {
        Path direct = resolveBundledPath(target.platformDir(), target.fileName());
        if (direct != null) {
            return direct;
        }
        if (target.legacyPlatformDir() != null) {
            return resolveBundledPath(target.legacyPlatformDir(), target.fileName());
        }
        return null;
    }

    private static Path resolveBundledPath(String platformDir, String fileName) throws IOException {
        Path direct = Path.of("src", "main", "resources", "natives", platformDir, fileName);
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath();
        }
        String resourcePath = "/natives/" + platformDir + "/" + fileName;
        byte[] libBytes;
        try (InputStream in = NativeAvx2.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            libBytes = in.readAllBytes();
        }
        String hashHex;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(libBytes);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            hashHex = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            hashHex = Integer.toHexString(java.util.Arrays.hashCode(libBytes));
        }
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "chunking-native", hashHex);
        Files.createDirectories(cacheDir);
        Path tmpLib = cacheDir.resolve(fileName);
        if (!Files.isRegularFile(tmpLib) || Files.size(tmpLib) != libBytes.length) {
            Path tmpPart = Files.createTempFile(cacheDir, fileName, ".tmp");
            Files.write(tmpPart, libBytes);
            try {
                Files.move(tmpPart, tmpLib, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tmpPart);
                if (!Files.isRegularFile(tmpLib)) {
                    throw e;
                }
            }
        }
        return tmpLib;
    }

    public static MemorySegment packOctaves(ImprovedNoise[] octs, double[] factors, double[] scales) {
        if (!AVAILABLE || octs == null || octs.length == 0) {
            return null;
        }
        MemorySegment seg = Arena.ofAuto().allocate((long) octs.length * OCTAVE_STRIDE, 32L);
        for (int i = 0; i < octs.length; i++) {
            ImprovedNoiseAccess acc = (ImprovedNoiseAccess) (Object) octs[i];
            long base = i * OCTAVE_STRIDE;
            seg.set(ValueLayout.JAVA_DOUBLE, base, factors[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 8L, scales[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 16L, acc.chunking$xo());
            seg.set(ValueLayout.JAVA_DOUBLE, base + 24L, acc.chunking$yo());
            seg.set(ValueLayout.JAVA_DOUBLE, base + 32L, acc.chunking$zo());
            seg.set(ValueLayout.JAVA_DOUBLE, base + 40L, 0.0D);
            byte[] perm = acc.chunking$perm();
            MemorySegment.copy(perm, 0, seg, ValueLayout.JAVA_BYTE, base + PERM_OFFSET, 256);
            MemorySegment.copy(perm, 0, seg, ValueLayout.JAVA_BYTE, base + PERM_OFFSET + 256L, 256);
        }
        return seg;
    }

    public static MemorySegment packBlendedOctaves(
            byte[][] mainPerms, double[] mainXo, double[] mainYo, double[] mainZo,
            double[] mainScales, double[] mainInvScales, double[] mainFyScales, double[] mainSmearScales,
            byte[][] minPerms, double[] minXo, double[] minYo, double[] minZo,
            double[] minScales, double[] minInvScales, double[] minYMultScales, double[] minSmearScales,
            byte[][] maxPerms, double[] maxXo, double[] maxYo, double[] maxZo,
            double[] maxScales, double[] maxInvScales, double[] maxYMultScales, double[] maxSmearScales
    ) {
        if (MH_BLENDED_COL == null) {
            return null;
        }
        int numMain = mainPerms != null ? mainPerms.length : 0;
        int numMin = minPerms != null ? minPerms.length : 0;
        int numMax = maxPerms != null ? maxPerms.length : 0;
        int total = numMain + numMin + numMax;
        if (total == 0) {
            return null;
        }
        MemorySegment seg = Arena.ofAuto().allocate((long) total * BLENDED_OCTAVE_STRIDE, 32L);
        int slot = 0;
        slot = writeBlendedGroup(seg, slot, mainPerms, mainXo, mainYo, mainZo, mainScales, mainInvScales, mainFyScales, mainSmearScales);
        slot = writeBlendedGroup(seg, slot, minPerms, minXo, minYo, minZo, minScales, minInvScales, minYMultScales, minSmearScales);
        writeBlendedGroup(seg, slot, maxPerms, maxXo, maxYo, maxZo, maxScales, maxInvScales, maxYMultScales, maxSmearScales);
        return seg;
    }

    private static int writeBlendedGroup(
            MemorySegment seg,
            int startSlot,
            byte[][] perms,
            double[] xo,
            double[] yo,
            double[] zo,
            double[] scales,
            double[] invScales,
            double[] yFactorScales,
            double[] smearScales
    ) {
        if (perms == null) {
            return startSlot;
        }
        int slot = startSlot;
        for (int i = 0; i < perms.length; i++, slot++) {
            long base = (long) slot * BLENDED_OCTAVE_STRIDE;
            double smear = smearScales[i];
            double invSmear = smear != 0.0D ? (1.0D / smear) : 0.0D;
            seg.set(ValueLayout.JAVA_DOUBLE, base, scales[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 8L, yFactorScales[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 16L, smear);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 24L, invScales[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 32L, xo[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 40L, yo[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 48L, zo[i]);
            seg.set(ValueLayout.JAVA_DOUBLE, base + 56L, invSmear);
            byte[] perm = perms[i];
            MemorySegment.copy(perm, 0, seg, ValueLayout.JAVA_BYTE, base + BLENDED_PERM_OFFSET, 256);
            MemorySegment.copy(perm, 0, seg, ValueLayout.JAVA_BYTE, base + BLENDED_PERM_OFFSET + 256L, 256);
        }
        return slot;
    }

    /**
     * Packs a {@link FlatBiomeTree}'s int-bounds node arrays into a single 64-byte-aligned
     * read-only off-heap {@link MemorySegment} managed by {@link Arena#ofAuto()}.
     *
     * <p>Per-node layout (64 bytes, 1 L1 cache line, matching {@code FlatRTreeNode} in C++):
     * {@code lo[6]} (0..23), {@code offsetSq} (24..31), {@code hi[6]} (32..55),
     * {@code childStart} (56..59), {@code childEnd} (60..63).</p>
     */
    public static MemorySegment packFlatRTreeNodes(
            int[] boundsInt,
            long[] offsetSq,
            int[] childStart,
            int[] childEnd
    ) {
        if (MH_RTREE_COL == null || boundsInt == null || offsetSq == null || childStart == null || childEnd == null) {
            return null;
        }
        int count = offsetSq.length;
        if (count <= 0) {
            return null;
        }
        MemorySegment seg = Arena.ofAuto().allocate((long) count * RTREE_NODE_STRIDE, 64L);
        for (int idx = 0; idx < count; idx++) {
            long base = (long) idx * RTREE_NODE_STRIDE;
            int io = idx * 12;
            seg.set(ValueLayout.JAVA_INT, base, boundsInt[io]);
            seg.set(ValueLayout.JAVA_INT, base + 4L, boundsInt[io + 2]);
            seg.set(ValueLayout.JAVA_INT, base + 8L, boundsInt[io + 4]);
            seg.set(ValueLayout.JAVA_INT, base + 12L, boundsInt[io + 6]);
            seg.set(ValueLayout.JAVA_INT, base + 16L, boundsInt[io + 8]);
            seg.set(ValueLayout.JAVA_INT, base + 20L, boundsInt[io + 10]);
            seg.set(ValueLayout.JAVA_LONG, base + 24L, offsetSq[idx]);
            seg.set(ValueLayout.JAVA_INT, base + 32L, boundsInt[io + 1]);
            seg.set(ValueLayout.JAVA_INT, base + 36L, boundsInt[io + 3]);
            seg.set(ValueLayout.JAVA_INT, base + 40L, boundsInt[io + 5]);
            seg.set(ValueLayout.JAVA_INT, base + 44L, boundsInt[io + 7]);
            seg.set(ValueLayout.JAVA_INT, base + 48L, boundsInt[io + 9]);
            seg.set(ValueLayout.JAVA_INT, base + 52L, boundsInt[io + 11]);
            seg.set(ValueLayout.JAVA_INT, base + 56L, childStart[idx]);
            seg.set(ValueLayout.JAVA_INT, base + 60L, childEnd[idx]);
        }
        return seg;
    }

    /**
     * Evaluates an entire Y-column of {@code count} depth queries against a native-packed
     * {@link FlatBiomeTree} in a single AVX2 call.
     *
     * @return the winning leaf index for {@code dQs[count - 1]}, or {@link Integer#MIN_VALUE}
     *         if any depth coordinate in {@code dQs[0..count-1]} overflows signed 32-bit {@code int}
     *         (in which case {@code outBiomes} is untouched and the caller falls back to Java).
     */
    public static int searchRTreeColumn(
            MemorySegment nodesSeg,
            int nodeCount,
            int seedLeafIdx,
            boolean allDepthNonNeg,
            boolean disjointDepthBands,
            long[] depthBandMins,
            long[] depthBandMaxs,
            int t0, int t1, int t2, int t3, int t5,
            long[] dQs,
            int count,
            Object[] leaves,
            Object[] outBiomes,
            int outOffset,
            int outStride
    ) {
        RTreeColumnScratch scratch = RTREE_SCRATCH_TL.get();
        scratch.ensureCapacity(count);
        MemorySegment dTargetsSeg = scratch.dTargets;
        for (int yi = 0; yi < count; yi++) {
            long d = dQs[yi];
            int di = (int) d;
            if ((d ^ di) != 0L) {
                return Integer.MIN_VALUE;
            }
            dTargetsSeg.set(ValueLayout.JAVA_INT, (long) yi * 4L, di);
        }

        MemorySegment bandMinsSeg = MemorySegment.NULL;
        MemorySegment bandMaxsSeg = MemorySegment.NULL;
        int bandCount = 0;
        if (disjointDepthBands && depthBandMins != null && depthBandMaxs != null) {
            bandCount = Math.min(depthBandMins.length, depthBandMaxs.length);
            if (bandCount > 0) {
                scratch.ensureBandCapacity(bandCount);
                bandMinsSeg = scratch.bandMins;
                bandMaxsSeg = scratch.bandMaxs;
                MemorySegment.copy(depthBandMins, 0, bandMinsSeg, ValueLayout.JAVA_LONG, 0L, bandCount);
                MemorySegment.copy(depthBandMaxs, 0, bandMaxsSeg, ValueLayout.JAVA_LONG, 0L, bandCount);
            }
        }

        MemorySegment outLeavesSeg = scratch.outLeaves;
        int lastLeafIdx;
        try {
            lastLeafIdx = (int) MH_RTREE_COL.invokeExact(
                    nodesSeg,
                    nodeCount,
                    seedLeafIdx,
                    allDepthNonNeg ? 1 : 0,
                    disjointDepthBands ? 1 : 0,
                    bandMinsSeg,
                    bandMaxsSeg,
                    bandCount,
                    t0, t1, t2, t3, t5,
                    dTargetsSeg,
                    outLeavesSeg,
                    count
            );
        } catch (Throwable t) {
            throw new IllegalStateException("AVX2 searchRTreeColumn invocation failed", t);
        }

        if (lastLeafIdx == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        for (int yi = 0; yi < count; yi++) {
            int leafIdx = outLeavesSeg.get(ValueLayout.JAVA_INT, (long) yi * 4L);
            outBiomes[outOffset + yi * outStride] = leafIdx >= 0 ? leaves[leafIdx] : null;
        }
        return lastLeafIdx;
    }

    public static void evalNormalColumn(
            MemorySegment octsSeg,
            int numOctaves,
            double xFixed,
            int[] by,
            double yScale,
            double zFixed,
            double[] out,
            int count
    ) {
        if (count <= 0) {
            return;
        }
        if (count > by.length || count > out.length || octsSeg.byteSize() < (long) numOctaves * OCTAVE_STRIDE) {
            throw new IllegalArgumentException("Native bounds exceeded in evalNormalColumn");
        }
        try {
            MH_NORMAL_COL.invokeExact(
                    octsSeg,
                    numOctaves,
                    xFixed,
                    MemorySegment.ofArray(by),
                    yScale,
                    zFixed,
                    MemorySegment.ofArray(out),
                    count
            );
        } catch (Throwable t) {
            throw new IllegalStateException("AVX2 evalNormalColumn invocation failed", t);
        }
    }

    public static void evalNormalColumnRange(
            MemorySegment octsSeg,
            int numOctaves,
            double xFixed,
            int[] by,
            int startIdx,
            double yScale,
            double zFixed,
            double[] out,
            int totalCount
    ) {
        int len = totalCount - startIdx;
        if (len <= 0) {
            return;
        }
        if (startIdx < 0 || totalCount > by.length || totalCount > out.length
                || octsSeg.byteSize() < (long) numOctaves * OCTAVE_STRIDE) {
            throw new IllegalArgumentException("Native bounds exceeded in evalNormalColumnRange");
        }
        try {
            MemorySegment bySeg = startIdx == 0
                    ? MemorySegment.ofArray(by)
                    : MemorySegment.ofArray(by).asSlice((long) startIdx * 4L, (long) len * 4L);
            MemorySegment outSeg = startIdx == 0
                    ? MemorySegment.ofArray(out)
                    : MemorySegment.ofArray(out).asSlice((long) startIdx * 8L, (long) len * 8L);
            MH_NORMAL_COL.invokeExact(
                    octsSeg,
                    numOctaves,
                    xFixed,
                    bySeg,
                    yScale,
                    zFixed,
                    outSeg,
                    len
            );
        } catch (Throwable t) {
            throw new IllegalStateException("AVX2 evalNormalColumnRange invocation failed", t);
        }
    }

    public static void evalNormalBatch3DInt(
            MemorySegment octsSeg,
            int numOctaves,
            int[] bx,
            int[] by,
            int[] bz,
            double xzScale,
            double yScale,
            double[] out,
            int count
    ) {
        if (count <= 0) {
            return;
        }
        if (count > bx.length || count > by.length || count > bz.length || count > out.length
                || octsSeg.byteSize() < (long) numOctaves * OCTAVE_STRIDE) {
            throw new IllegalArgumentException("Native bounds exceeded in evalNormalBatch3DInt");
        }
        try {
            MH_NORMAL_BATCH3D_INT.invokeExact(
                    octsSeg,
                    numOctaves,
                    MemorySegment.ofArray(bx),
                    MemorySegment.ofArray(by),
                    MemorySegment.ofArray(bz),
                    xzScale,
                    yScale,
                    MemorySegment.ofArray(out),
                    count
            );
        } catch (Throwable t) {
            throw new IllegalStateException("AVX2 evalNormalBatch3DInt invocation failed", t);
        }
    }

    public static void evalBlendedColumn(
            MemorySegment octsSeg,
            int numMain,
            int numMin,
            int numMax,
            double dx,
            double dz,
            double fx,
            double fz,
            int[] by,
            int startIdx,
            double[] out,
            int totalCount
    ) {
        if (totalCount <= startIdx) {
            return;
        }
        if (startIdx < 0 || totalCount > by.length || totalCount > out.length
                || octsSeg.byteSize() < (long) (numMain + numMin + numMax) * OCTAVE_STRIDE) {
            throw new IllegalArgumentException("Native bounds exceeded in evalBlendedColumn");
        }
        try {
            MH_BLENDED_COL.invokeExact(
                    octsSeg,
                    numMain,
                    numMin,
                    numMax,
                    dx,
                    dz,
                    fx,
                    fz,
                    MemorySegment.ofArray(by),
                    startIdx,
                    MemorySegment.ofArray(out),
                    totalCount
            );
        } catch (Throwable t) {
            throw new IllegalStateException("AVX2 evalBlendedColumn invocation failed", t);
        }
    }
}
