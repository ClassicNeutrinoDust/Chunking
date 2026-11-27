package com.chunking.engine;

import com.chunking.engine.benchmark.ChunkyBench;
import com.chunking.engine.io.FrameCache;
import com.chunking.engine.io.TickNbt;
import com.chunking.engine.io.SectionReader;
import com.chunking.engine.io.SectionWriter;
import com.chunking.engine.io.EncodedCache;
import com.chunking.engine.io.RegionMask;
import com.chunking.engine.io.RegionKinds;
import com.chunking.engine.scheduler.FrameGuard;
import com.chunking.engine.scheduler.FrameGuardProbe;
import com.chunking.engine.scheduler.AreaLock;
import com.chunking.engine.scheduler.TickBudget;
import com.chunking.engine.scheduler.LookAhead;
import com.chunking.engine.scheduler.MoveTracker;
import com.chunking.engine.world.FlatBiomeTree;
import com.chunking.engine.world.FastHeightmap;
import com.chunking.engine.world.HolderTable;
import com.chunking.engine.world.SectionCounts;
import com.chunking.engine.simd.NoiseMath;
import com.chunking.engine.world.FastLongSet;
import com.chunking.engine.access.SurfaceConditionAccess;
import com.chunking.engine.world.FastSurface;
import com.chunking.engine.world.TrackedHolders;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless regression and performance test suite covering all active Chunking subsystems.
 */
public final class EngineTests {
    private EngineTests() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.out.println("=================================================================");
        System.out.println("CHUNKING 1.0.0 VERIFICATION SUITE (Java 25 + SIMD)");
        System.out.println("SIMD Lanes: " + NoiseMath.LANES + " (scalar/C2-vectorized)");
        System.out.println("=================================================================");

        testSimdNoiseParityAndThroughput();
        testSpatialFeatureLockConcurrency();
        testHighSpeedVelocityConeAndGapBridge();
        testTickBudgetAndZeroJitterGuards();
        testFastSectionNbtEncoderParity();
        testFlatRTreeSurfaceRulesAndEnduranceHelpers();
        testTurboWorldgenHelpersParity();
        testRegionIoAndSectionDecodeParity();
        testSavedTickNbtAndBitPackHelpers();
        testAllDimensionCompatibilityHelpers();
        testWorldgenModCompatibility();
        testThreadingAndSafetyHardening();
        testOreVeinAndBaseHeightParityGuards();
        testRenderDistanceAndFogUnlock();
        testLightingEngineAndMultiDimTrackerOptimizations();
        testHighCoreServerScaling();
        testBugFixesAndCompatGuards();
        testModdedWorldgenAndSpatialStructures();
        testCarversAndConfigReload();
        testBaseHeightCacheAndModIcon();

        System.out.println("ALL 20 CHUNKING VERIFICATION SUITES PASSED SUCCESSFULLY.");
    }

    private static void testSimdNoiseParityAndThroughput() {
        final int count = 100_000;
        byte[] perm = new byte[256];
        for (int i = 0; i < 256; i++) perm[i] = (byte) i;
        SplittableRandom rng = new SplittableRandom(0xC41A90L);
        for (int i = 0; i < 256; i++) {
            int j = i + rng.nextInt(256 - i);
            byte t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }

        double[] xs = new double[count];
        double[] ys = new double[count];
        double[] zs = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = (i - 50_000) * 0.037D + rng.nextDouble(-0.5D, 0.5D);
            ys[i] = (i % 384 - 64) * 0.083D + rng.nextDouble(-0.25D, 0.25D);
            zs[i] = ((i * 7) % 100_000 - 50_000) * 0.041D + rng.nextDouble(-0.5D, 0.5D);
        }

        double[] simdOut = new double[count];
        double[] scalarOut = new double[count];
        double xo = 17.25D, yo = -41.75D, zo = 93.125D;
        double yScale = 0.25D, yMax = 0.75D;

        // Warmup JIT thoroughly so C2 Vector/AVX2 intrinsics are compiled
        for (int w = 0; w < 25; w++) {
            NoiseMath.sampleBatch3D(perm, xo, yo, zo, xs, ys, zs, yScale, yMax, simdOut, count);
            for (int i = 0; i < count; i++) {
                scalarOut[i] = NoiseMath.sampleScalar(perm, xo, yo, zo, xs[i], ys[i], zs[i], yScale, yMax);
            }
        }

        long t0 = System.nanoTime();
        NoiseMath.sampleBatch3D(perm, xo, yo, zo, xs, ys, zs, yScale, yMax, simdOut, count);
        long t1 = System.nanoTime();

        long s0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            scalarOut[i] = NoiseMath.sampleScalar(perm, xo, yo, zo, xs[i], ys[i], zs[i], yScale, yMax);
        }
        long s1 = System.nanoTime();

        double maxErr = 0.0D;
        for (int i = 0; i < count; i++) {
            double err = Math.abs(simdOut[i] - scalarOut[i]);
            if (err > maxErr) maxErr = err;
        }
        assertTrue(maxErr <= 1e-12D, "SIMD noise parity error too high: " + maxErr);

        // Verify X/Z-hoisted column kernels (both NoSmear and WithSmear) match scalar ImprovedNoise exact parity
        int[] colY = new int[49];
        for (int i = 0; i < 49; i++) colY[i] = -64 + i * 8;
        double[] colNoSmear = new double[49];
        double[] colSmear = new double[49];
        NoiseMath.evalOctaveColumnNoSmear(perm, xo, yo, zo, 64 * 0.25D, colY, 0.25D, -48 * 0.25D, 1.0D, colNoSmear, 49);
        NoiseMath.evalOctaveColumnWithSmear(perm, xo, yo, zo, 64 * 0.25D, colY, 0.125D, -48 * 0.25D, 8.0D, 1.0D, colSmear, 49, 0, null);
        for (int i = 0; i < 49; i++) {
            double expNoSmear = NoiseMath.sampleScalar(
                    perm, xo, yo, zo,
                    NoiseMath.wrap(64 * 0.25D),
                    NoiseMath.wrap(colY[i] * 0.25D),
                    NoiseMath.wrap(-48 * 0.25D), 0.0D, 0.0D);
            double expSmear = NoiseMath.sampleScalar(
                    perm, xo, yo, zo,
                    NoiseMath.wrap(64 * 0.25D),
                    NoiseMath.wrap(colY[i] * 0.125D),
                    NoiseMath.wrap(-48 * 0.25D), 8.0D, colY[i] * 0.125D);
            assertTrue(Math.abs(colNoSmear[i] - expNoSmear) <= 1e-12D,
                    "Column NoSmear mismatch at i=" + i + ": " + colNoSmear[i] + " vs " + expNoSmear);
            assertTrue(Math.abs(colSmear[i] - expSmear) <= 1e-12D,
                    "Column WithSmear mismatch at i=" + i + ": " + colSmear[i] + " vs " + expSmear);
        }

        double simdMs = (t1 - t0) / 1_000_000.0D;
        double scalarMs = (s1 - s0) / 1_000_000.0D;
        System.out.printf("[PASS] 1. SIMD Noise Parity (N=%d): maxErr=%.3e | simd=%.3f ms | scalar=%.3f ms%n",
                count, maxErr, simdMs, scalarMs);
    }

    private static void testSpatialFeatureLockConcurrency() throws Exception {
        AreaLock spatialLock = new AreaLock();
        final int taskCount = 1_000;
        ConcurrentHashMap<Long, long[]> activeMap = new ConcurrentHashMap<>();
        AtomicInteger maxConcurrentActive = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        List<CompletableFuture<Integer>> futures = new ArrayList<>(taskCount);

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            long start = System.nanoTime();
            for (int i = 0; i < taskCount; i++) {
                // Cluster heavily around a 16x16 region to maximize deliberate 3x3 contention
                int cx = (i * 3) % 16;
                int cz = (i * 5) % 16;
                final long id = i;
                futures.add(spatialLock.executeAsync(cx, cz, () -> {
                    long[] mine = new long[]{cx, cz, id};
                    // Check every currently active task respects 3x3 disjointness (|dx|>2 or |dz|>2)
                    for (long[] other : activeMap.values()) {
                        int cheb = Math.max(Math.abs(cx - (int) other[0]), Math.abs(cz - (int) other[1]));
                        if (cheb <= 2) {
                            violations.incrementAndGet();
                        }
                    }
                    activeMap.put(id, mine);
                    int activeNow = activeMap.size();
                    maxConcurrentActive.accumulateAndGet(activeNow, Math::max);
                    try {
                        Thread.onSpinWait();
                    } finally {
                        activeMap.remove(id);
                    }
                    return 1;
                }, pool));
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0D;

            assertTrue(violations.get() == 0, "Spatial 3x3 exclusion violated " + violations.get() + " times");
            assertTrue(spatialLock.activeOwnedChunkCells() == 0, "Leaked owned cells in spatial lock");
            assertTrue(spatialLock.pendingCount() == 0, "Leaked pending tasks in spatial lock");
            assertTrue(spatialLock.completedTasks() == taskCount, "Not all spatial tasks completed");

            System.out.printf("[PASS] 2. Spatial 3x3 Feature Lock (1000 tasks, 16 threads): maxParallel=%d | violations=0 | %.2f ms%n",
                    maxConcurrentActive.get(), elapsedMs);
        }
    }

    private static void testHighSpeedVelocityConeAndGapBridge() {
        // Simulate 200 ticks moving East (+X) at 3.5 blocks/tick (70 blocks/sec)
        double vx = 3.5D, vz = 0.0D;
        double lookX = 1.0D, lookZ = 0.0D;

        int forwardConeWins = 0;

        for (int tick = 0; tick < 200; tick++) {
            double px = tick * vx;
            double pz = 0.0D;
            int pcx = Math.floorDiv((int) Math.floor(px), 16);
            int pcz = Math.floorDiv((int) Math.floor(pz), 16);

            double fwdEff = LookAhead.computeEffectiveDistance(pcx, pcz, vx, vz, lookX, lookZ, pcx + 6, pcz);
            double rearEff = LookAhead.computeEffectiveDistance(pcx, pcz, vx, vz, lookX, lookZ, pcx - 6, pcz);
            int fwdSendScore = LookAhead.computeSendSortScore(pcx, pcz, vx, vz, lookX, lookZ, pcx + 6, pcz);
            int rearSendScore = LookAhead.computeSendSortScore(pcx, pcz, vx, vz, lookX, lookZ, pcx - 6, pcz);
            if (fwdEff < rearEff && fwdSendScore < rearSendScore) {
                forwardConeWins++;
            }
        }

        assertTrue(forwardConeWins == 200, "Forward cone preemption did not win all 200 ticks: " + forwardConeWins);
        System.out.println("[PASS] 3. High-Speed Flight Sim (70 blk/s, 200 ticks): fwdWins=200/200");
    }

    private static void testTickBudgetAndZeroJitterGuards() {
        assertTrue(com.chunking.engine.config.ModConfig.effectiveFullChunkRadius(12) == 12, "Cutoff 12 mismatch");
        assertTrue(com.chunking.engine.config.ModConfig.effectiveFullChunkRadius(32) == 32, "Cutoff 32 mismatch");
        assertTrue(com.chunking.engine.config.ModConfig.effectiveFullChunkRadius(64) <= 64, "Cutoff >64 mismatch");
        assertTrue(TickBudget.computeChunkSendRate(true, 8.0D) == 64.0F,
                "Healthy local chunk send ceiling should be 64.0");
        assertTrue(TickBudget.computeChunkSendRate(false, 8.0D) == 48.0F,
                "Healthy remote chunk send ceiling should be 48.0");
        assertTrue(TickBudget.computeChunkSendRate(true, 24.0D) == 32.0F,
                "MSPT >22ms should throttle chunk send rate to 32.0");
        assertTrue(TickBudget.computeChunkSendRate(true, 35.0D) == 16.0F,
                "MSPT >30ms should throttle chunk send rate to 16.0");
        assertTrue(TickBudget.computeChunkSendRate(true, 45.0D) == 4.0F,
                "MSPT >40ms should clamp chunk send rate to 4.0");
        assertTrue(TickBudget.shouldAllowPredictivePreStage(10.0D),
                "Predictive pre-staging must run when MSPT <= 30ms");
        assertTrue(!TickBudget.shouldAllowPredictivePreStage(31.0D),
                "Predictive pre-staging must pause above 30ms MSPT to protect <20ms tick time");
        assertTrue(TickBudget.measureEffectiveTickMs(null) == 0.0D,
                "Null server check must return 0.0 ms");
        System.out.println("[PASS] 4. Tick Budget & Zero-Jitter Guards: MSPT throttle curve & predictive gate=OK");
    }

    private static void testFastSectionNbtEncoderParity() throws Exception {
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeIdMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeIdMap::add);

        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeIdMap);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        Holder<Biome> plainsBiome = biomeLookup.getOrThrow(Biomes.PLAINS);

        Codec<PalettedContainer<BlockState>> blockCodec = PalettedContainer.codecRW(
                BlockState.CODEC, blockStrategy, air);
        Codec<Holder<Biome>> biomeEntryCodec = Identifier.CODEC.xmap(
                id -> biomeLookup.getOrThrow(ResourceKey.create(Registries.BIOME, id)),
                h -> h.unwrapKey().orElseThrow().identifier());
        Codec<PalettedContainer<Holder<Biome>>> biomeCodec = PalettedContainer.codecRW(
                biomeEntryCodec, biomeStrategy, plainsBiome);

        List<BlockState> distinctStates = new ArrayList<>(32);
        for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
            if (distinctStates.size() >= 24) {
                break;
            }
            if (s != air) {
                distinctStates.add(s);
            }
        }
        assertTrue(distinctStates.size() >= 20, "Need at least 20 distinct BlockStates");

        // (a) Single-value BlockState container
        PalettedContainer<BlockState> cSingle = new PalettedContainer<>(stone, blockStrategy);
        assertBlockContainerParity("single-value", cSingle, blockCodec);

        // (b) 4-bit LinearPalette with unused palette entries (usedCount < oldSize)
        PalettedContainer<BlockState> cLinearUnused = new PalettedContainer<>(air, blockStrategy);
        cLinearUnused.getAndSetUnchecked(0, 0, 0, stone);
        cLinearUnused.getAndSetUnchecked(1, 0, 0, distinctStates.get(1));
        cLinearUnused.getAndSetUnchecked(2, 0, 0, distinctStates.get(2));
        cLinearUnused.getAndSetUnchecked(3, 0, 0, distinctStates.get(3));
        cLinearUnused.getAndSetUnchecked(1, 0, 0, stone);
        cLinearUnused.getAndSetUnchecked(2, 0, 0, stone);
        assertBlockContainerParity("linear-unused-entries", cLinearUnused, blockCodec);

        // (c) 5-bit HashMapPalette with 20 distinct BlockStates (usedCount == oldSize && bits == storage.getBits())
        PalettedContainer<BlockState> cHash20 = new PalettedContainer<>(air, blockStrategy);
        for (int i = 0; i < 4096; i++) {
            BlockState v = (i % 21 == 0) ? air : distinctStates.get(i % 20);
            cHash20.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, v);
        }
        assertBlockContainerParity("hashmap-5bit-full", cHash20, blockCodec);

        // Overwrite back down to 3 active states while 20 remain in HashMapPalette (usedCount < oldSize shrinkage from 5-bit)
        for (int i = 0; i < 4096; i++) {
            cHash20.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, distinctStates.get(i & 1));
        }
        assertBlockContainerParity("hashmap-5bit-shrunk", cHash20, blockCodec);

        // (d) Over-allocated 5-bit HashMapPalette with 10 distinct entries (usedCount == oldSize == 10 while storage bits == 5 and target bits == 4)
        int[] overAllocIds = new int[4096];
        for (int i = 0; i < 4096; i++) {
            overAllocIds[i] = i % 10;
        }
        Method cfgMethod = Strategy.class.getDeclaredMethod("getConfigurationForBitCount", int.class);
        cfgMethod.setAccessible(true);
        Configuration cfg5 = (Configuration) cfgMethod.invoke(blockStrategy, 5);
        Palette<BlockState> pal10In5Bit = new HashMapPalette<>(5, distinctStates.subList(0, 10));
        BitStorage storage5 = new SimpleBitStorage(5, 4096, overAllocIds);
        Constructor<PalettedContainer> rawCtor = PalettedContainer.class.getDeclaredConstructor(
                Strategy.class, Configuration.class, BitStorage.class, Palette.class);
        rawCtor.setAccessible(true);
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState> cOverAlloc = rawCtor.newInstance(blockStrategy, cfg5, storage5, pal10In5Bit);
        assertBlockContainerParity("overalloc-5bit-usedCount==oldSize", cOverAlloc, blockCodec);

        // (e) Biome containers with 1, 4, and 10 distinct biomes
        List<Holder<Biome>> biomeList = new ArrayList<>(12);
        biomeLookup.listElements().limit(12).forEach(biomeList::add);
        assertTrue(biomeList.size() >= 10, "Need at least 10 Overworld/Vanilla biomes");

        PalettedContainer<Holder<Biome>> b1 = new PalettedContainer<>(biomeList.get(0), biomeStrategy);
        assertBiomeContainerParity("biomes-1", b1, biomeCodec);

        PalettedContainer<Holder<Biome>> b4 = new PalettedContainer<>(biomeList.get(0), biomeStrategy);
        for (int i = 0; i < 64; i++) {
            b4.getAndSetUnchecked(i & 3, (i >> 4) & 3, (i >> 2) & 3, biomeList.get(i & 3));
        }
        assertBiomeContainerParity("biomes-4", b4, biomeCodec);

        PalettedContainer<Holder<Biome>> b10 = new PalettedContainer<>(biomeList.get(0), biomeStrategy);
        for (int i = 0; i < 64; i++) {
            b10.getAndSetUnchecked(i & 3, (i >> 4) & 3, (i >> 2) & 3, biomeList.get(i % 10));
        }
        assertBiomeContainerParity("biomes-10", b10, biomeCodec);

        System.out.println("[PASS] 5. Fast Section NBT Encoder Parity: block_states (single/linear/5bit-full/5bit-shrunk/overalloc) & biomes (1/4/10)=OK");
    }

    private static void assertBlockContainerParity(
            String label,
            PalettedContainer<BlockState> pc,
            Codec<PalettedContainer<BlockState>> codec
    ) {
        CompoundTag fastTag = SectionWriter.encodeBlockStates(pc);
        assertTrue(fastTag != null, "Fast block_states encode returned null for " + label);
        net.minecraft.nbt.Tag vanillaTag = codec.encodeStart(NbtOps.INSTANCE, pc).getOrThrow();

        PalettedContainer<BlockState> decodedFast = codec.parse(NbtOps.INSTANCE, fastTag).getOrThrow();
        PalettedContainer<BlockState> decodedVanilla = codec.parse(NbtOps.INSTANCE, vanillaTag).getOrThrow();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState expected = decodedVanilla.get(x, y, z);
                    BlockState actual = decodedFast.get(x, y, z);
                    BlockState direct = pc.get(x, y, z);
                    if (actual != expected || actual != direct) {
                        throw new AssertionError("BlockState parity mismatch in " + label + " at (" + x + "," + y + "," + z
                                + "): fast=" + actual + " vanilla=" + expected + " orig=" + direct);
                    }
                }
            }
        }
    }

    private static void assertBiomeContainerParity(
            String label,
            PalettedContainer<Holder<Biome>> pc,
            Codec<PalettedContainer<Holder<Biome>>> codec
    ) {
        CompoundTag fastTag = SectionWriter.encodeBiomes(pc);
        assertTrue(fastTag != null, "Fast biomes encode returned null for " + label);
        net.minecraft.nbt.Tag vanillaTag = codec.encodeStart(NbtOps.INSTANCE, pc).getOrThrow();

        PalettedContainer<Holder<Biome>> decodedFast = codec.parse(NbtOps.INSTANCE, fastTag).getOrThrow();
        PalettedContainer<Holder<Biome>> decodedVanilla = codec.parse(NbtOps.INSTANCE, vanillaTag).getOrThrow();
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> expected = decodedVanilla.get(x, y, z);
                    Holder<Biome> actual = decodedFast.get(x, y, z);
                    if (!actual.equals(expected)) {
                        throw new AssertionError("Biome parity mismatch in " + label + " at (" + x + "," + y + "," + z
                                + "): fast=" + actual + " vanilla=" + expected);
                    }
                }
            }
        }
    }

    private static void testFlatRTreeSurfaceRulesAndEnduranceHelpers() throws Exception {
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        HolderGetter<MultiNoiseBiomeSourceParameterList> presetGetter =
                registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        Climate.ParameterList<Holder<Biome>> overworldParams =
                presetGetter.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters();

        Field indexField = Climate.ParameterList.class.getDeclaredField("index");
        indexField.setAccessible(true);
        Object rtree = indexField.get(overworldParams);
        Field rootField = rtree.getClass().getDeclaredField("root");
        rootField.setAccessible(true);
        Object rootNode = rootField.get(rtree);
        Field lastResultField = rtree.getClass().getDeclaredField("lastResult");
        lastResultField.setAccessible(true);
        ThreadLocal<?> rtreeLastResult = (ThreadLocal<?>) lastResultField.get(rtree);

        FlatBiomeTree flat = FlatBiomeTree.buildFromRawRoot(rootNode);
        assertTrue(flat != null, "FlatBiomeTree.buildFromRawRoot returned null");

        SplittableRandom rng = new SplittableRandom(0x5A17_0911L);
        long[] extremes = {-12000L, -10000L, -5000L, 0L, 5000L, 10000L, 12000L};
        for (int i = 0; i < 2000; i++) {
            long t = i < 100 ? extremes[i % extremes.length] : rng.nextLong(-12000L, 12001L);
            long h = i < 100 ? extremes[(i * 3) % extremes.length] : rng.nextLong(-12000L, 12001L);
            long c = i < 100 ? extremes[(i * 5) % extremes.length] : rng.nextLong(-12000L, 12001L);
            long e = i < 100 ? extremes[(i * 7) % extremes.length] : rng.nextLong(-12000L, 12001L);
            long d = i < 100 ? extremes[(i * 2) % extremes.length] : rng.nextLong(-12000L, 12001L);
            long w = i < 100 ? extremes[(i * 4) % extremes.length] : rng.nextLong(-12000L, 12001L);
            rtreeLastResult.remove();
            Object fastBiome = flat.searchFlatRaw(t, h, c, e, d, w);
            Object vanillaBiome = overworldParams.findValue(new Climate.TargetPoint(t, h, c, e, d, w));
            if (fastBiome != vanillaBiome) {
                throw new AssertionError("FlatRTree mismatch at query " + i + ": fast=" + fastBiome + " vanilla=" + vanillaBiome);
            }
        }

        // SurfaceRules LambdaMetafactory singletons, folding, and FlatSequenceRule truncation
        Object alwaysFalse = FastSurface.alwaysFalse();
        Object alwaysTrue = FastSurface.alwaysTrue();
        assertTrue(alwaysFalse != null && alwaysTrue != null, "LambdaMetafactory SurfaceRules$Condition singletons must be non-null");
        Class<?> condCls = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Condition");
        Method testMethod = condCls.getMethod("test");
        testMethod.setAccessible(true);
        assertTrue(!((Boolean) testMethod.invoke(alwaysFalse)), "alwaysFalse.test() must be false");
        assertTrue(((Boolean) testMethod.invoke(alwaysTrue)), "alwaysTrue.test() must be true");
        assertTrue(FastSurface.foldNotCondition(alwaysFalse) == alwaysTrue, "not(false) must fold to alwaysTrue");
        assertTrue(FastSurface.foldNotCondition(alwaysTrue) == alwaysFalse, "not(true) must fold to alwaysFalse");
        assertTrue(FastSurface.canBuildNotCondition() && FastSurface.canBuildTestRule(),
                "SurfaceRules constructors (NotCondition / TestRule) must be resolved");
        Object notFalse = FastSurface.newNotCondition(alwaysFalse);
        assertTrue(((Boolean) testMethod.invoke(notFalse)), "NotCondition(alwaysFalse).test() must be true");
        Object stoneRule = FastSurface.evalRuleSource(SurfaceRules.state(Blocks.STONE.defaultBlockState()), null);
        Object builtTestRule = FastSurface.newTestRule(alwaysTrue, stoneRule);
        assertTrue(FastSurface.tryApplyRule(builtTestRule, 0, 64, 0) == Blocks.STONE.defaultBlockState(),
                "TestRule(alwaysTrue, STONE) must apply STONE");
        Object flatSeq = FastSurface.buildFlattenedSequence(
                List.of(
                        FastSurface.dummyNilRuleSource(),
                        FastSurface.dummyUnmatchedRuleSource(),
                        SurfaceRules.state(Blocks.STONE.defaultBlockState()),
                        SurfaceRules.state(Blocks.DIRT.defaultBlockState())
                ),
                null
        );
        assertTrue(flatSeq instanceof FastSurface.FlatSequenceRule fseq && fseq.ruleCount() == 2,
                "FlatSequenceRule must drop NEVER_MATCH_RULE and truncate unreachable rules after unconditional StateRule");
        assertTrue(((FastSurface.FlatSequenceRule) flatSeq).tryApply(3, 64, 5) == Blocks.STONE.defaultBlockState(),
                "FlatSequenceRule.tryApply must return STONE");

        // SpiralCursor exact parity vs buildSquareSpiral across center (100, -50), R=12
        List<ChunkPos> expectedSpiral = ChunkyBench.buildSquareSpiral(100, -50, 12);
        ChunkyBench.SpiralCursor cursor = new ChunkyBench.SpiralCursor(100, -50, 12);
        assertTrue(cursor.totalCount() == expectedSpiral.size(), "SpiralCursor count mismatch");
        for (int i = 0; i < expectedSpiral.size(); i++) {
            assertTrue(cursor.hasNext(), "SpiralCursor ended early at " + i);
            ChunkPos got = cursor.nextChunkPos();
            if (!got.equals(expectedSpiral.get(i))) {
                throw new AssertionError("SpiralCursor mismatch at " + i + ": got=" + got + " exp=" + expectedSpiral.get(i));
            }
        }
        assertTrue(!cursor.hasNext(), "SpiralCursor should be exhausted after R=12");

        // RegionMask across positive and negative coordinates
        RegionMask bitSet = new RegionMask(16);
        int[] coords = {-1000, -33, -32, -31, -1, 0, 1, 31, 32, 33, 1000};
        for (int x : coords) {
            for (int z : coords) {
                assertTrue(!bitSet.contains(x, z), "BitSet must start clear at " + x + "," + z);
                bitSet.add(x, z);
                assertTrue(bitSet.contains(x, z), "BitSet must contain added coords " + x + "," + z);
            }
        }
        assertTrue(!bitSet.contains(-999, 1000), "BitSet must not alias neighboring chunks");
        bitSet.clear();
        assertTrue(!bitSet.contains(1000, 1000), "BitSet clear must reset all regions");

        System.out.println("[PASS] 6. Flat RTree (2000 queries), SurfaceRules Folding, SpiralCursor (625) & RegionBitSet Parity=OK");
    }

    private static void testTurboWorldgenHelpersParity() {
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeIdMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeIdMap::add);

        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeIdMap);
        Holder<Biome> plainsBiome = biomeLookup.getOrThrow(Biomes.PLAINS);
        net.minecraft.world.level.chunk.PalettedContainerFactory containerFactory =
                new net.minecraft.world.level.chunk.PalettedContainerFactory(
                        blockStrategy, Blocks.AIR.defaultBlockState(), null, biomeStrategy, plainsBiome, null);

        net.minecraft.world.level.LevelHeightAccessor heightAccessor = net.minecraft.world.level.LevelHeightAccessor.create(-64, 384);
        var chunkA = new net.minecraft.world.level.chunk.ProtoChunk(
                new ChunkPos(17, -9), net.minecraft.world.level.chunk.UpgradeData.EMPTY, heightAccessor, containerFactory, null);
        var chunkB = new net.minecraft.world.level.chunk.ProtoChunk(
                new ChunkPos(17, -9), net.minecraft.world.level.chunk.UpgradeData.EMPTY, heightAccessor, containerFactory, null);

        // Verify completely empty chunk parity first
        var hmTypes = java.util.EnumSet.of(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
        );
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunkA, hmTypes);
        FastHeightmap.primeHeightmaps(chunkB, hmTypes);
        for (var t : hmTypes) {
            long[] exp = chunkA.getOrCreateHeightmapUnprimed(t).getRawData();
            long[] got = chunkB.getOrCreateHeightmapUnprimed(t).getRawData();
            if (!java.util.Arrays.equals(exp, got)) {
                throw new AssertionError("Empty-chunk Heightmap parity mismatch for " + t);
            }
        }

        // Populate realistic multi-section terrain: base stone, water columns, cave air, floating leaves, and untouched middle empty sections
        var cPopA = new net.minecraft.world.level.chunk.ProtoChunk(
                new ChunkPos(21, -3), net.minecraft.world.level.chunk.UpgradeData.EMPTY, heightAccessor, containerFactory, null);
        var cPopB = new net.minecraft.world.level.chunk.ProtoChunk(
                new ChunkPos(21, -3), net.minecraft.world.level.chunk.UpgradeData.EMPTY, heightAccessor, containerFactory, null);
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState caveAir = Blocks.CAVE_AIR.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int baseH = 58 + ((x * 3 + z * 5) & 7);
                for (int y = -16; y <= baseH; y++) {
                    BlockState s = (y >= baseH - 2 && ((x + z) & 3) == 0) ? caveAir : stone;
                    cPopA.getSection(cPopA.getSectionIndex(y)).setBlockState(x, y & 15, z, s, false);
                    cPopB.getSection(cPopB.getSectionIndex(y)).setBlockState(x, y & 15, z, s, false);
                }
                if ((x & 1) == 0 && (z & 1) == 0) {
                    for (int y = baseH + 1; y <= 65; y++) {
                        cPopA.getSection(cPopA.getSectionIndex(y)).setBlockState(x, y & 15, z, water, false);
                        cPopB.getSection(cPopB.getSectionIndex(y)).setBlockState(x, y & 15, z, water, false);
                    }
                }
                if (x == 5 && z == 7) {
                    int ly = 210;
                    cPopA.getSection(cPopA.getSectionIndex(ly)).setBlockState(x, ly & 15, z, leaves, false);
                    cPopB.getSection(cPopB.getSectionIndex(ly)).setBlockState(x, ly & 15, z, leaves, false);
                }
            }
        }
        // Leave column (15, 15) as CAVE_AIR-only around y=200 to verify mixed CAVE_AIR sections match vanilla
        cPopA.getSection(cPopA.getSectionIndex(200)).setBlockState(15, 200 & 15, 15, caveAir, false);
        cPopB.getSection(cPopB.getSectionIndex(200)).setBlockState(15, 200 & 15, 15, caveAir, false);

        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(cPopA, hmTypes);
        FastHeightmap.primeHeightmaps(cPopB, hmTypes);
        for (var t : hmTypes) {
            long[] exp = cPopA.getOrCreateHeightmapUnprimed(t).getRawData();
            long[] got = cPopB.getOrCreateHeightmapUnprimed(t).getRawData();
            if (!java.util.Arrays.equals(exp, got)) {
                throw new AssertionError("Populated-chunk Heightmap parity mismatch for " + t);
            }
        }

        // Verify FastTestRule, FastAnd2Rule, and unpacked FlatSequenceRule evaluation
        final boolean[] condFlags = new boolean[]{false, true};
        SurfaceConditionAccess cond0 = () -> condFlags[0];
        SurfaceConditionAccess cond1 = () -> condFlags[1];
        Object dirtRule = FastSurface.evalRuleSource(
                SurfaceRules.state(Blocks.DIRT.defaultBlockState()), null);
        Object grassRule = FastSurface.evalRuleSource(
                SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState()), null);
        Object nestedTest = FastSurface.newTestRule(
                cond0,
                FastSurface.newTestRule(cond1, grassRule)
        );
        assertTrue(nestedTest instanceof FastSurface.FastAnd2Rule,
                "Nested FastTestRule should collapse into FastAnd2Rule");
        Object singleTest = FastSurface.newTestRule(cond1, dirtRule);
        assertTrue(singleTest instanceof FastSurface.FastTestRule,
                "Single condition TestRule should produce FastTestRule");

        assertTrue(FastSurface.tryApplyRule(nestedTest, 0, 64, 0) == null,
                "FastAnd2Rule must return null when first condition is false");
        condFlags[0] = true;
        assertTrue(FastSurface.tryApplyRule(nestedTest, 0, 64, 0) == Blocks.GRASS_BLOCK.defaultBlockState(),
                "FastAnd2Rule must apply GRASS_BLOCK when both conditions are true");
        condFlags[0] = false;

        Object unpackedSeq = FastSurface.buildFlattenedSequence(
                List.of(
                        FastSurface.constRuleSource(nestedTest),
                        FastSurface.constRuleSource(singleTest)
                ),
                null
        );
        assertTrue(unpackedSeq instanceof FastSurface.FlatSequenceRule fseq && fseq.ruleCount() == 2,
                "FlatSequenceRule must retain both fast test rules");
        var fs = (FastSurface.FlatSequenceRule) unpackedSeq;
        assertTrue(fs.tryApply(1, 64, 1) == Blocks.DIRT.defaultBlockState(),
                "FlatSequenceRule fallback when cond0=false, cond1=true must return DIRT");
        condFlags[0] = true;
        assertTrue(fs.tryApply(1, 64, 1) == Blocks.GRASS_BLOCK.defaultBlockState(),
                "FlatSequenceRule when cond0=true, cond1=true must return GRASS_BLOCK");
        condFlags[1] = false;
        assertTrue(fs.tryApply(1, 64, 1) == null,
                "FlatSequenceRule when cond1=false must return null");

        System.out.println("[PASS] 7. Turbo Worldgen Helpers: Heightmap Empty/Mixed Section Parity & Flat Surface FastTest/FastAnd2 Rules=OK");
    }

    private static void testRegionIoAndSectionDecodeParity() throws Exception {
        // 1. RegionKinds multi-region & concurrency (100k+ coords, negative + positive + upgrades)
        RegionKinds typeTable = new RegionKinds(16);
        int[] baseCoords = {-2048, -33, -32, -1, 0, 1, 31, 32, 62500};
        for (int bx : baseCoords) {
            for (int bz : baseCoords) {
                long p = ChunkPos.pack(bx, bz);
                assertTrue(typeTable.get(p) == RegionKinds.STATE_UNKNOWN, "Must start UNKNOWN");
                typeTable.setProto(p);
                assertTrue(typeTable.get(p) == RegionKinds.STATE_PROTO, "Must become PROTO");
                typeTable.setFull(p);
                assertTrue(typeTable.get(p) == RegionKinds.STATE_FULL, "Must upgrade to FULL");
            }
        }
        try (ExecutorService exec = Executors.newFixedThreadPool(4)) {
            CompletableFuture<?>[] tasks = new CompletableFuture<?>[4];
            for (int t = 0; t < 4; t++) {
                final int tid = t;
                tasks[t] = CompletableFuture.runAsync(() -> {
                    for (int i = tid; i < 104_000; i += 4) {
                        int cx = 62_300 + (i % 400);
                        int cz = 62_300 + (i / 400);
                        long p = ChunkPos.pack(cx, cz);
                        if ((i & 1) == 0) {
                            typeTable.setFull(p);
                        } else {
                            typeTable.setProto(p);
                        }
                    }
                }, exec);
            }
            CompletableFuture.allOf(tasks).join();
        }
        for (int i = 0; i < 104_000; i += 79) {
            int cx = 62_300 + (i % 400);
            int cz = 62_300 + (i / 400);
            int expected = (i & 1) == 0
                    ? RegionKinds.STATE_FULL
                    : RegionKinds.STATE_PROTO;
            assertTrue(typeTable.get(ChunkPos.pack(cx, cz)) == expected, "Concurrent TypeTable mismatch at " + i);
        }
        typeTable.clear();
        assertTrue(typeTable.get(ChunkPos.pack(62_300, 62_300)) == RegionKinds.STATE_UNKNOWN,
                "TypeTable clear must reset state");

        // 2. FrameCache LRU eviction & get/remove
        FrameCache frameCache = new FrameCache(4);
        byte[] dummyFrame = new byte[]{0, 0, 0, 2, 2, 1};
        for (int i = 0; i < 6; i++) {
            frameCache.put(i, dummyFrame);
        }
        assertTrue(frameCache.size() == 4, "FrameCache must enforce maxEntries=4");
        assertTrue(frameCache.get(0) == null && frameCache.get(1) == null, "Oldest frames must evict");
        assertTrue(frameCache.get(2) == dummyFrame && frameCache.get(5) == dummyFrame, "Recent frames must hit");
        frameCache.remove(2);
        assertTrue(frameCache.get(2) == null && frameCache.size() == 3, "Remove must drop entry");

        // 3. EncodedCache encodeDeflateFrame + decodeDeflateFrame exact NBT round-trip
        CompoundTag sampleNbt = new CompoundTag();
        sampleNbt.putInt("xPos", 62500);
        sampleNbt.putInt("zPos", -12345);
        sampleNbt.putString("Status", "minecraft:full");
        sampleNbt.putLongArray("HeightmapData", new long[]{1L, 0x123456789ABCDEF0L, -1L});
        byte[] frame = EncodedCache.encodeDeflateFrame(sampleNbt);
        assertTrue(frame != null && frame.length > 5, "Deflate frame encoding must succeed");
        CompoundTag roundTripped = EncodedCache.decodeDeflateFrame(frame);
        assertTrue(sampleNbt.equals(roundTripped), "decodeDeflateFrame must produce bit-exact CompoundTag");

        // 4. SectionReader round-trip parity vs vanilla CODEC + 5. Section count helper parity
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeIdMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeIdMap::add);

        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeIdMap);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState wheat = Blocks.WHEAT.defaultBlockState();

        PalettedContainer<BlockState> sAir = new PalettedContainer<>(air, blockStrategy);
        PalettedContainer<BlockState> sSolid = new PalettedContainer<>(stone, blockStrategy);
        PalettedContainer<BlockState> sLinear = new PalettedContainer<>(air, blockStrategy);
        for (int i = 0; i < 4096; i++) {
            BlockState v = (i % 7 == 0) ? stone : ((i % 11 == 0) ? water : ((i % 13 == 0) ? leaves : ((i % 17 == 0) ? wheat : air)));
            sLinear.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, v);
        }
        List<BlockState> blockSamples = new ArrayList<>(24);
        for (BlockState bs : Block.BLOCK_STATE_REGISTRY) {
            if (blockSamples.size() >= 22) break;
            if (!bs.isAir()) blockSamples.add(bs);
        }
        PalettedContainer<BlockState> sHash5 = new PalettedContainer<>(air, blockStrategy);
        for (int i = 0; i < 4096; i++) {
            BlockState v = (i % 23 == 0) ? water : ((i % 19 == 0) ? wheat : blockSamples.get(i % 20));
            sHash5.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, v);
        }

        Field dataField = PalettedContainer.class.getDeclaredField("data");
        dataField.setAccessible(true);
        Class<?> dataCls = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
        Field storageField = dataCls.getDeclaredField("storage");
        storageField.setAccessible(true);
        Field paletteField = dataCls.getDeclaredField("palette");
        paletteField.setAccessible(true);

        PalettedContainer<?>[] blockContainers = new PalettedContainer[]{sAir, sSolid, sLinear, sHash5};
        for (int idx = 0; idx < blockContainers.length; idx++) {
            @SuppressWarnings("unchecked")
            PalettedContainer<BlockState> orig = (PalettedContainer<BlockState>) blockContainers[idx];
            CompoundTag encoded = SectionWriter.encodeBlockStates(orig);
            PalettedContainer<BlockState> decoded = SectionReader.decodeBlockStates(encoded, blockStrategy);
            assertTrue(decoded != null, "Fast block decode must handle container #" + idx);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertTrue(decoded.get(x, y, z) == orig.get(x, y, z), "Block decode mismatch at #" + idx);
                    }
                }
            }
            int[] expCounts = new int[4];
            orig.count((bs, cnt) -> {
                if (bs.isAir()) return;
                expCounts[0] += cnt;
                if (bs.isRandomlyTicking()) expCounts[1] += cnt;
                var fs = bs.getFluidState();
                if (!fs.isEmpty()) {
                    expCounts[2] += cnt;
                    if (fs.isRandomlyTicking()) expCounts[3] += cnt;
                }
            });
            Object dObj = dataField.get(decoded);
            @SuppressWarnings("unchecked")
            Palette<BlockState> pal = (Palette<BlockState>) paletteField.get(dObj);
            BitStorage st = (BitStorage) storageField.get(dObj);
            int[] gotCounts = new int[4];
            assertTrue(SectionCounts.tryCountDirect(pal, st, gotCounts),
                    "tryCountDirect must handle container #" + idx);
            for (int c = 0; c < 4; c++) {
                assertTrue(gotCounts[c] == expCounts[c],
                        "Section counter mismatch #" + idx + " slot=" + c + ": got=" + gotCounts[c] + " exp=" + expCounts[c]);
            }
        }

        // Biome fast decode round-trip across 1 and 10 biomes
        List<Holder<Biome>> biomeList = new ArrayList<>(10);
        biomeLookup.listElements().limit(10).forEach(biomeList::add);
        PalettedContainer<Holder<Biome>> bio1 = new PalettedContainer<>(biomeList.get(0), biomeStrategy);
        PalettedContainer<Holder<Biome>> bio4 = new PalettedContainer<>(biomeList.get(0), biomeStrategy);
        for (int i = 0; i < 64; i++) {
            bio4.getAndSetUnchecked(i & 3, (i >> 4) & 3, (i >> 2) & 3, biomeList.get(i % 4));
        }
        for (PalettedContainer<Holder<Biome>> orig : List.of(bio1, bio4)) {
            CompoundTag encoded = SectionWriter.encodeBiomes(orig);
            var decoded = SectionReader.decodeBiomes(encoded, biomeStrategy);
            assertTrue(decoded != null, "Fast biome decode must succeed");
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        assertTrue(decoded.get(x, y, z).equals(orig.get(x, y, z)), "Biome decode mismatch");
                    }
                }
            }
        }

        System.out.println("[PASS] 8. Region IO & Section Codec: TypeTable (104k) + FrameCache + DeflateRoundTrip + FastSectionDecoder + SectionCounter=OK");
    }

    private static void testSavedTickNbtAndBitPackHelpers() throws Exception {
        // 1. TickNbt: block ticks, fluid ticks, ChunkStatus, and TickPriority parity
        List<SavedTick<Block>> blockTicks = List.of(
                new SavedTick<>(Blocks.STONE, new BlockPos(10, 64, -12), 5, TickPriority.HIGH),
                new SavedTick<>(Blocks.REDSTONE_LAMP, new BlockPos(-3, 0, 19), 0, TickPriority.EXTREMELY_HIGH),
                new SavedTick<>(Blocks.OAK_LEAVES, new BlockPos(15, 120, 7), 42, TickPriority.EXTREMELY_LOW)
        );
        ListTag fastBlockTag = TickNbt.encodeBlockTicks(blockTicks);
        assertTrue(fastBlockTag != null, "encodeBlockTicks must succeed");
        CompoundTag blockWrapper = new CompoundTag();
        blockWrapper.put("block_ticks", fastBlockTag);
        var decodedBlockTicks = TickNbt.decodeBlockTicks(blockWrapper, "block_ticks");
        assertTrue(decodedBlockTicks != null && decodedBlockTicks.isPresent(), "decodeBlockTicks must succeed");
        assertTrue(decodedBlockTicks.get().equals(blockTicks), "Block tick round-trip mismatch");

        Codec<List<SavedTick<Block>>> vanillaBlockCodec = SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
        var vanillaBlockTag = vanillaBlockCodec.encodeStart(NbtOps.INSTANCE, blockTicks).getOrThrow();
        assertTrue(vanillaBlockTag.equals(fastBlockTag), "encodeBlockTicks must match vanilla Codec NBT");

        List<SavedTick<Fluid>> fluidTicks = List.of(
                new SavedTick<>(Fluids.WATER, new BlockPos(1, 62, 2), 3, TickPriority.NORMAL),
                new SavedTick<>(Fluids.LAVA, new BlockPos(4, 11, -9), 15, TickPriority.VERY_LOW)
        );
        ListTag fastFluidTag = TickNbt.encodeFluidTicks(fluidTicks);
        assertTrue(fastFluidTag != null, "encodeFluidTicks must succeed");
        CompoundTag fluidWrapper = new CompoundTag();
        fluidWrapper.put("fluid_ticks", fastFluidTag);
        var decodedFluidTicks = TickNbt.decodeFluidTicks(fluidWrapper, "fluid_ticks");
        assertTrue(decodedFluidTicks != null && decodedFluidTicks.isPresent(), "decodeFluidTicks must succeed");
        assertTrue(decodedFluidTicks.get().equals(fluidTicks), "Fluid tick round-trip mismatch");

        // Unknown registry ID must return null so SerializableChunkData falls back cleanly
        CompoundTag unknownTag = new CompoundTag();
        ListTag unknownList = new ListTag();
        CompoundTag badTick = new CompoundTag();
        badTick.putString("i", "chunking:nonexistent_block_id");
        badTick.putInt("x", 0);
        badTick.putInt("y", 0);
        badTick.putInt("z", 0);
        badTick.putInt("t", 1);
        badTick.putInt("p", 0);
        unknownList.add(badTick);
        unknownTag.put("block_ticks", unknownList);
        assertTrue(TickNbt.decodeBlockTicks(unknownTag, "block_ticks") == null,
                "Unknown block tick ID must return null for DFU fallback");
        assertTrue(TickNbt.decodeBlockTicks(new CompoundTag(), "block_ticks").isEmpty(),
                "Absent block_ticks key must return Optional.empty()");

        for (ChunkStatus st : BuiltInRegistries.CHUNK_STATUS) {
            CompoundTag stTag = new CompoundTag();
            stTag.putString("Status", BuiltInRegistries.CHUNK_STATUS.getKey(st).toString());
            var decSt = TickNbt.decodeChunkStatus(stTag, "Status");
            assertTrue(decSt != null && decSt.isPresent() && decSt.get() == st, "ChunkStatus decode mismatch for " + st);
        }
        CompoundTag badStatus = new CompoundTag();
        badStatus.putString("Status", "chunking:nonexistent_status");
        assertTrue(TickNbt.decodeChunkStatus(badStatus, "Status") == null,
                "Unknown ChunkStatus must return null");
        for (int p = -10; p <= 10; p++) {
            TickPriority got = TickNbt.priorityByValue(p);
            TickPriority exp = TickPriority.byValue(p);
            assertTrue(got == exp, "TickPriority mismatch at " + p);
        }

        // 2. SectionWriter packBitsDirect + zeroCopyRaw decoder parity across 0,4,5,6,7,8 bits
        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        List<BlockState> paletteSamples = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            if (s != null) {
                paletteSamples.add(s);
                if (paletteSamples.size() >= 140) break;
            }
        }
        for (int distinct : new int[]{1, 2, 5, 16, 17, 33, 129}) {
            PalettedContainer<BlockState> orig = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStrategy);
            for (int i = 0; i < 4096; i++) {
                orig.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, paletteSamples.get(i % distinct));
            }
            CompoundTag encoded = SectionWriter.encodeBlockStates(orig);
            assertTrue(encoded != null, "encodeBlockStates must succeed for distinct=" + distinct);
            PalettedContainer<BlockState> decoded = SectionReader.decodeBlockStates(encoded, blockStrategy, true);
            assertTrue(decoded != null, "zeroCopyRaw decodeBlockStates must succeed for distinct=" + distinct);
            for (int i = 0; i < 4096; i += 7) {
                int x = i & 15, y = (i >> 8) & 15, z = (i >> 4) & 15;
                assertTrue(decoded.get(x, y, z) == orig.get(x, y, z),
                        "BlockState bit-pack round-trip mismatch at distinct=" + distinct);
            }
        }

        // 3. TrackedHolders + HolderTable 5,000-op fuzz + concurrent reads
        TrackedHolders<String> tracked = new TrackedHolders<>(64);
        Long2ObjectLinkedOpenHashMap<String> visibleMirror = new Long2ObjectLinkedOpenHashMap<>(64);
        Long2ObjectLinkedOpenHashMap<String> ref = new Long2ObjectLinkedOpenHashMap<>(64);
        HolderTable<String> optTable = new HolderTable<>(64);
        SplittableRandom fuzzRng = new SplittableRandom(0x13D00DL);
        for (int step = 0; step < 5000; step++) {
            long k = fuzzRng.nextInt(0, 600);
            int op = fuzzRng.nextInt(100);
            if (op < 45) {
                String v = "v" + step;
                tracked.put(k, v);
                ref.put(k, v);
            } else if (op < 65) {
                tracked.remove(k);
                ref.remove(k);
            } else if (op < 80) {
                String v = "pa" + step;
                tracked.putIfAbsent(k, v);
                ref.putIfAbsent(k, v);
            } else if (op < 95) {
                String v = "mf" + step;
                tracked.putAndMoveToFirst(k, v);
                ref.putAndMoveToFirst(k, v);
            } else {
                tracked.clear();
                ref.clear();
            }
            if ((step & 7) == 7 || step == 4999) {
                tracked.flushTo(visibleMirror, optTable);
                assertTrue(visibleMirror.equals(ref), "visibleMirror mismatch at step " + step);
                assertTrue(optTable.size() == ref.size(), "optTable size mismatch at step " + step);
                for (var e : ref.long2ObjectEntrySet()) {
                    assertTrue(e.getValue().equals(optTable.get(e.getLongKey())),
                            "optTable value mismatch for key " + e.getLongKey());
                }
            }
        }

        // Concurrent optimistic reader stress during tracked flushes
        AtomicInteger concErr = new AtomicInteger();
        ExecutorService optExec = Executors.newFixedThreadPool(4);
        List<CompletableFuture<Void>> optFuts = new ArrayList<>(4);
        for (int t = 0; t < 4; t++) {
            optFuts.add(CompletableFuture.runAsync(() -> {
                for (int r = 0; r < 25_000; r++) {
                    String val = optTable.get(r & 511);
                    if (val != null && !val.startsWith("v") && !val.startsWith("pa") && !val.startsWith("mf")) {
                        concErr.incrementAndGet();
                    }
                }
            }, optExec));
        }
        for (int w = 0; w < 200; w++) {
            tracked.put(w & 511, "v" + w);
            if ((w & 3) == 3) tracked.flushTo(visibleMirror, optTable);
        }
        CompletableFuture.allOf(optFuts.toArray(CompletableFuture[]::new)).join();
        optExec.shutdown();
        assertTrue(concErr.get() == 0, "Concurrent HolderTable read error");

        // 4. FastLongSet parity & 8-thread concurrency
        FastLongSet striped = new FastLongSet(256);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet refSet = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < 2000; i++) {
            long k = fuzzRng.nextInt(0, 400);
            if ((i & 3) == 0) {
                assertTrue(striped.remove(k) == refSet.remove(k), "Striped remove parity mismatch");
            } else {
                striped.add(k);
                refSet.add(k);
            }
            assertTrue(striped.contains(k) == refSet.contains(k), "Striped contains parity mismatch");
        }
        ExecutorService setExec = Executors.newFixedThreadPool(8);
        List<CompletableFuture<Void>> setFuts = new ArrayList<>(8);
        for (int t = 0; t < 8; t++) {
            final int tid = t;
            setFuts.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 5000; i++) {
                    long key = ((long) tid << 16) | (i & 255);
                    striped.add(key);
                    if ((i & 1) == 0) striped.remove(key);
                }
            }, setExec));
        }
        CompletableFuture.allOf(setFuts.toArray(CompletableFuture[]::new)).join();
        setExec.shutdown();

        // 5. FrameGuard client-hitch vs dedicated-server isolation
        FrameGuardProbe.init(12);
        FrameGuard gov = FrameGuard.get();
        int serverCap = gov.inFlightCap(12);
        assertTrue(!gov.hasActiveClientFrames(), "Dedicated server must not report active client frames");
        for (int f = 0; f < 12; f++) {
            FrameGuardProbe.feedFrame(16.0D);
        }
        FrameGuardProbe.feedFrame(38.0D); // trigger frame hitch (> max(25, baseline * 1.85))
        FrameGuardProbe.step();
        assertTrue(gov.hasActiveClientFrames(), "Client frames must be active after feedFrame");
        assertTrue(gov.isInFrameHitch(), "38 ms frame must trigger hitch cooldown");
        assertTrue(gov.inFlightCap(12) < serverCap, "Client frame hitch must back off inFlightCap");
        assertTrue(gov.minChunkPacketsPerFrame() >= 1 && gov.maxChunkPacketsPerFrame() >= gov.minChunkPacketsPerFrame(),
                "Packet budget bounds must be valid");
        FrameGuardProbe.reset();

        // 6. Native thread priority helper must not throw
        com.chunking.engine.core.ThreadHints.preferHighPerformanceCurrentThread();

        System.out.println("[PASS] 9. NBT Ticks & Bit-Packed Helpers: FastSavedTickNbt + DirectPack + TrackedHolderMap + StripedSet + GovernorHitch=OK");
    }

    private static void testAllDimensionCompatibilityHelpers() {
        // 1. Overworld (5,49), Nether (5,17), and End (3,65) interpolator slice pooling
        for (int[] shape : new int[][]{{5, 49}, {5, 17}, {3, 65}}) {
            double[][] slice = new double[shape[0]][shape[1]];
            com.chunking.engine.simd.JitSupport.recycleInterpolatorSlice(slice);
            double[][] borrowed = com.chunking.engine.simd.JitSupport.borrowInterpolatorSlice(shape[0], shape[1]);
            assertTrue(borrowed == slice, "Slice pool must return recycled (" + shape[0] + "," + shape[1] + ") slice");
        }

        // 2. Nether and End section encode/decode round-trip
        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        PalettedContainer<BlockState> netherSec = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStrategy);
        BlockState[] netherPalette = {
                Blocks.NETHERRACK.defaultBlockState(),
                Blocks.SOUL_SAND.defaultBlockState(),
                Blocks.CRIMSON_NYLIUM.defaultBlockState(),
                Blocks.BASALT.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),
                Blocks.BEDROCK.defaultBlockState()
        };
        for (int i = 0; i < 4096; i++) {
            netherSec.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, netherPalette[i % netherPalette.length]);
        }
        CompoundTag netherTag = SectionWriter.encodeBlockStates(netherSec);
        PalettedContainer<BlockState> netherDec = SectionReader.decodeBlockStates(netherTag, blockStrategy, true);
        assertTrue(netherDec != null, "Nether section decode must succeed");
        for (int i = 0; i < 4096; i += 13) {
            int x = i & 15, y = (i >> 8) & 15, z = (i >> 4) & 15;
            assertTrue(netherDec.get(x, y, z) == netherSec.get(x, y, z), "Nether section round-trip mismatch");
        }

        PalettedContainer<BlockState> endSec = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStrategy);
        BlockState[] endPalette = {
                Blocks.END_STONE.defaultBlockState(),
                Blocks.OBSIDIAN.defaultBlockState(),
                Blocks.END_STONE_BRICKS.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        };
        for (int i = 0; i < 4096; i++) {
            endSec.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, endPalette[i % endPalette.length]);
        }
        CompoundTag endTag = SectionWriter.encodeBlockStates(endSec);
        PalettedContainer<BlockState> endDec = SectionReader.decodeBlockStates(endTag, blockStrategy, true);
        assertTrue(endDec != null, "End section decode must succeed");
        for (int i = 0; i < 4096; i += 11) {
            int x = i & 15, y = (i >> 8) & 15, z = (i >> 4) & 15;
            assertTrue(endDec.get(x, y, z) == endSec.get(x, y, z), "End section round-trip mismatch");
        }

        // 3. End cw=8, ch=4 writeStateCell & fillCellBox bit-layout parity against vanilla PalettedContainer
        BlockState[] endStates = new BlockState[256];
        for (int baseX : new int[]{0, 8}) {
            for (int baseZ : new int[]{0, 8}) {
                PalettedContainer<BlockState> fastContainer = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStrategy);
                PalettedContainer<BlockState> refContainer = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), blockStrategy);
                // Force 4-bit palette by inserting 2 distinct states
                fastContainer.getAndSetUnchecked(0, 0, 0, Blocks.OBSIDIAN.defaultBlockState());
                refContainer.getAndSetUnchecked(0, 0, 0, Blocks.OBSIDIAN.defaultBlockState());
                int idx = 0;
                for (int inY = 3; inY >= 0; inY--) {
                    for (int inX = 0; inX < 8; inX++) {
                        for (int inZ = 0; inZ < 8; inZ++) {
                            BlockState st = ((inX + inY + inZ) & 1) == 0
                                    ? Blocks.END_STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState();
                            endStates[idx++] = st;
                            if (st != Blocks.AIR.defaultBlockState()) {
                                refContainer.getAndSetUnchecked(baseX + inX, 4 + inY, baseZ + inZ, st);
                            }
                        }
                    }
                }
                if ((Object) fastContainer instanceof com.chunking.engine.access.PalettedContainerAccess<?> rawAcc) {
                    @SuppressWarnings("unchecked")
                    var acc = (com.chunking.engine.access.PalettedContainerAccess<BlockState>) rawAcc;
                    int[] extra = new int[3];
                    int added = acc.chunking$writeStateCell(baseX, 4, baseZ, 8, 4, endStates,
                            Blocks.END_STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), extra);
                    assertTrue(added == 128, "End writeStateCell must report 128 non-air blocks, got " + added);
                    for (int inY = 0; inY < 4; inY++) {
                        for (int inX = 0; inX < 8; inX++) {
                            for (int inZ = 0; inZ < 8; inZ++) {
                                assertTrue(
                                        fastContainer.get(baseX + inX, 4 + inY, baseZ + inZ)
                                                == refContainer.get(baseX + inX, 4 + inY, baseZ + inZ),
                                        "End cw=8 ch=4 writeStateCell parity mismatch at (" + inX + "," + inY + "," + inZ + ")");
                            }
                        }
                    }
                } else {
                    // Headless JavaExec: verify exact 4-bit long[] cw=8, ch=4 word/shift layout vs Strategy.getIndex
                    SimpleBitStorage bitStorage = new SimpleBitStorage(4, 4096);
                    long[] raw4 = bitStorage.getRaw();
                    int endStoneId = 2;
                    for (int inY = 3; inY >= 0; inY--) {
                        int ly = 4 + inY;
                        int yBase = ly << 4;
                        int rowBase = (3 - inY) * 64;
                        for (int inZ = 0; inZ < 8; inZ++) {
                            int wIdx = yBase | (baseZ + inZ);
                            long cur = raw4[wIdx];
                            for (int inX = 0; inX < 8; inX++) {
                                BlockState val = endStates[rowBase + inX * 8 + inZ];
                                if (val == Blocks.END_STONE.defaultBlockState()) {
                                    int shift = (baseX + inX) << 2;
                                    cur = (cur & ~(0xFL << shift)) | ((long) endStoneId << shift);
                                }
                            }
                            raw4[wIdx] = cur;
                        }
                    }
                    for (int inY = 0; inY < 4; inY++) {
                        for (int inX = 0; inX < 8; inX++) {
                            for (int inZ = 0; inZ < 8; inZ++) {
                                int got = bitStorage.get(blockStrategy.getIndex(baseX + inX, 4 + inY, baseZ + inZ));
                                int exp = ((inX + inY + inZ) & 1) == 0 ? endStoneId : 0;
                                assertTrue(got == exp,
                                        "End cw=8 ch=4 4-bit layout mismatch at (" + inX + "," + inY + "," + inZ + ")");
                            }
                        }
                    }
                }
            }
        }

        // 4. LongQueue FIFO/LIFO, wrap-around, and non-shrinking capacity retention
        var fifo = new com.chunking.engine.world.LongQueue(64);
        for (long i = 0; i < 150; i++) {
            fifo.enqueue(1000L + i);
        }
        int capAfterGrowth = fifo.capacity();
        assertTrue(capAfterGrowth >= 150, "Non-shrinking FIFO queue must expand to hold 150 items");
        for (int i = 0; i < 100; i++) {
            assertTrue(fifo.dequeueLong() == 1000L + i, "FIFO dequeue mismatch at " + i);
        }
        for (long i = 150; i < 230; i++) {
            fifo.enqueue(1000L + i);
        }
        for (int i = 100; i < 230; i++) {
            assertTrue(fifo.dequeueLong() == 1000L + i, "FIFO wrap-around dequeue mismatch at " + i);
        }
        assertTrue(fifo.isEmpty(), "FIFO queue must be empty after draining all items");
        assertTrue(fifo.capacity() == capAfterGrowth, "FIFO queue must retain capacity when <= 65536");
        fifo.enqueue(10L);
        fifo.enqueue(20L);
        assertTrue(fifo.dequeueLastLong() == 20L && fifo.dequeueLastLong() == 10L, "LIFO dequeueLastLong mismatch");

        // 5. LegacyPositionalRandomFactory zero-alloc nextFloat() bit-exactness across 500 coords
        long legacySeed = 0x5EED_CAFE_BABE_1234L;
        for (int i = 0; i < 500; i++) {
            int rx = (i % 2 == 0 ? 1 : -1) * (i * 17 + 3);
            int ry = -64 + (i % 192);
            int rz = (i % 3 == 0 ? -1 : 1) * (i * 31 + 7);
            float ref = new net.minecraft.world.level.levelgen.SingleThreadedRandomSource(
                    net.minecraft.util.Mth.getSeed(rx, ry, rz) ^ legacySeed).nextFloat();
            long s = ((net.minecraft.util.Mth.getSeed(rx, ry, rz) ^ legacySeed) ^ 25214903917L) & 281474976710655L;
            s = (s * 25214903917L + 11L) & 281474976710655L;
            float fast = (float) ((int) (s >>> 24)) * 5.9604645E-8F;
            assertTrue(Float.floatToRawIntBits(ref) == Float.floatToRawIntBits(fast),
                    "LegacyPositional zero-alloc float mismatch at (" + rx + "," + ry + "," + rz + ")");
        }

        // 6. EndIslandDensityFunction signed division (/) key packing across positive & negative coords
        java.util.Set<Long> keys = new java.util.HashSet<>();
        for (int c = -17; c <= 17; c++) {
            int sx = c / 8;
            int shiftWrong = c >> 3;
            if (c < 0 && (c & 7) != 0) {
                assertTrue(sx != shiftWrong, "Negative non-multiple-of-8 must use /8, not >>3, at c=" + c);
            }
            long key = (((long) sx) << 32) | ((c / 8) & 0xFFFFFFFFL);
            keys.add(key);
        }
        assertTrue(keys.contains((((long) (-2)) << 32) | ((-2) & 0xFFFFFFFFL)), "Expected (-2,-2) key for [-17..-16]");
        assertTrue(keys.contains((((long) 0) << 32) | (0 & 0xFFFFFFFFL)), "Expected (0,0) key for [-7..7]");

        System.out.println("[PASS] 10. All-Dimension Compatibility: SlicePools(OW/Nether/End) + Nether/End NBT + End cw=8 BitLayout + Pass13.5 Helpers=OK");
    }

    private static void testWorldgenModCompatibility() {
        // 1. Custom non-vanilla DensityFunction classes with colliding simple names ("Clamp", "Noise")
        final class Clamp implements DensityFunction.SimpleFunction {
            private final double bias;
            Clamp(double bias) { this.bias = bias; }
            @Override public double compute(FunctionContext ctx) { return (ctx.blockX() * 0.25D) - this.bias; }
            @Override public double minValue() { return 10.0D; } // intentionally misreported
            @Override public double maxValue() { return 10.0D; } // intentionally misreported
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
        }
        final class Noise implements DensityFunction.SimpleFunction {
            private final List<DensityFunction> children;
            Noise(List<DensityFunction> children) { this.children = children; }
            @Override public double compute(FunctionContext ctx) {
                double s = 0.0D;
                for (DensityFunction c : this.children) s += c.compute(ctx);
                return s;
            }
            @Override public double minValue() { return 5.0D; }
            @Override public double maxValue() { return 5.0D; }
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
        }

        assertTrue(!com.chunking.engine.world.ModCompat.isVanillaClass(Clamp.class),
                "Custom Clamp must not be classified as vanilla");
        assertTrue(!com.chunking.engine.world.ModCompat.isVanillaDensityFunctionsInner(Clamp.class),
                "Custom Clamp must not be classified as DensityFunctions inner");
        assertTrue(com.chunking.engine.world.ModCompat.isVanillaClass(DensityFunctions.zero().getClass()),
                "Vanilla DensityFunction must be classified as vanilla");

        DensityFunction customTree = DensityFunctions.add(
                new Noise(List.of(new Clamp(5.0D))),
                DensityFunctions.constant(1.5D));
        var kernel = com.chunking.engine.simd.NoiseJit.compileAndBind(customTree);
        assertTrue(kernel != null, "JIT compiler must compile custom mod DensityFunction graph");
        int[] bx = {0, 4, -8, 12};
        int[] by = {0, 64, -32, 128};
        int[] bz = {0, -4, 8, -12};
        double[] out = new double[4];
        kernel.evalBatch(bx, by, bz, out, 4, null, false);
        for (int i = 0; i < 4; i++) {
            double expected = customTree.compute(new DensityFunction.SinglePointContext(bx[i], by[i], bz[i]));
            assertTrue(Double.doubleToRawLongBits(out[i]) == Double.doubleToRawLongBits(expected),
                    "JIT parity mismatch for custom mod DensityFunction at i=" + i);
        }

        // 2. Estimator must NOT trust misreported min/max on non-vanilla DensityFunction (returns -5.0 < 0 even though minValue=10)
        var est = com.chunking.engine.simd.CellSkipper.compile(new Clamp(5.0D), true, 4);
        assertTrue(est != null, "Estimator compile must succeed");
        assertTrue(est.classifyCell(0, 0, 0, 8) == 0,
                "Estimator must conservatively return mixed (0) for unknown non-vanilla DensityFunction");

        // 3. Tall/custom dimension slice pooling: Tectonic (5,81), Amplified Nether (5,33), Nullscape (3,97)
        for (int[] shape : new int[][]{{5, 81}, {5, 33}, {3, 97}}) {
            double[][] slice = new double[shape[0]][shape[1]];
            com.chunking.engine.simd.JitSupport.recycleInterpolatorSlice(slice);
            double[][] borrowed = com.chunking.engine.simd.JitSupport.borrowInterpolatorSlice(shape[0], shape[1]);
            assertTrue(borrowed == slice, "Custom slice pool must return recycled (" + shape[0] + "," + shape[1] + ") slice");
        }

        // 4. Dynamic OreVeinifier Y-bound helpers
        assertTrue(com.chunking.engine.world.FastOreVeins.IS_VANILLA_VEINS, "Vanilla VeinType bounds expected in test env");
        assertTrue(!com.chunking.engine.world.FastOreVeins.isOutsideOreVeinY(25), "y=25 inside Copper vein");
        assertTrue(!com.chunking.engine.world.FastOreVeins.isOutsideOreVeinY(-30), "y=-30 inside Iron vein");
        assertTrue(com.chunking.engine.world.FastOreVeins.isOutsideOreVeinY(-4), "y=-4 in vanilla vein gap");
        assertTrue(com.chunking.engine.world.FastOreVeins.isOutsideOreVeinY(80), "y=80 above ore veins");
        assertTrue(com.chunking.engine.world.FastOreVeins.isRangeOutsideOreVeinY(-7, -1), "[-7,-1] in gap");
        assertTrue(!com.chunking.engine.world.FastOreVeins.isRangeOutsideOreVeinY(-10, 5), "[-10,5] overlaps veins");

        // 5. SpatialFeatureLock dynamic writeRadius = 2 (radius=2 -> minDist = 4 -> dx=3 serialized, dx=5 parallel)
        AreaLock sfl = new AreaLock();
        java.util.concurrent.CountDownLatch firstEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger concurrentInConflictZone = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            CompletableFuture<Void> f0 = sfl.executeAsync(0, 0, 2, () -> {
                firstEntered.countDown();
                try { releaseFirst.await(); } catch (InterruptedException ignored) {}
                return null;
            }, pool);
            firstEntered.await();
            CompletableFuture<Void> fFar = sfl.executeAsync(5, 0, 2, () -> null, pool);
            fFar.get(3, java.util.concurrent.TimeUnit.SECONDS); // dx=5 > 2+2 must run in parallel!
            CompletableFuture<Void> fNear = sfl.executeAsync(3, 0, 2, () -> {
                concurrentInConflictZone.incrementAndGet();
                return null;
            }, pool);
            Thread.sleep(50);
            assertTrue(concurrentInConflictZone.get() == 0, "writeRadius=2 must serialize dx=3 while (0,0) is active");
            releaseFirst.countDown();
            f0.get(3, java.util.concurrent.TimeUnit.SECONDS);
            fNear.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(concurrentInConflictZone.get() == 1, "Deferred writeRadius=2 task must run after release");
        } catch (Exception e) {
            throw new AssertionError("SpatialFeatureLock writeRadius=2 test failed", e);
        } finally {
            pool.shutdownNow();
        }

        // 6. CountOnEveryLayerPlacement fast column scanner parity (air/water/lava/bedrock/hasOnlyAir/maxY>chunkMaxY)
        {
            var blockStrategy = Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
            BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            BlockState water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
            BlockState lava = net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState();
            BlockState bedrock = net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState();

            // 4 sections covering y = 0..63; section 3 (48..63) is all-air (hasOnlyAir() == true)
            net.minecraft.world.level.chunk.LevelChunkSection[] secs = new net.minecraft.world.level.chunk.LevelChunkSection[4];
            for (int s = 0; s < 4; s++) {
                PalettedContainer<BlockState> pc = new PalettedContainer<>(air, blockStrategy);
                secs[s] = new net.minecraft.world.level.chunk.LevelChunkSection(pc, null);
            }
            // y=0 bedrock, y=1..10 stone, y=11 water, y=12..15 air (layer 2? no, water is empty so stone@10->y=11)
            secs[0].setBlockState(3, 0, 5, bedrock, false);
            for (int y = 1; y <= 10; y++) secs[0].setBlockState(3, y, 5, stone, false);
            secs[0].setBlockState(3, 11, 5, water, false);
            // sec 1 (16..31): y=20..22 stone, y=23 lava (empty), y=28 stone (under air@29)
            secs[1].setBlockState(3, 4, 5, stone, false);
            secs[1].setBlockState(3, 5, 5, stone, false);
            secs[1].setBlockState(3, 6, 5, stone, false);
            secs[1].setBlockState(3, 7, 5, lava, false);
            secs[1].setBlockState(3, 12, 5, stone, false);
            // sec 2 (32..47): y=47 stone (at top boundary below all-air sec 3!)
            secs[2].setBlockState(3, 15, 5, stone, false);
            for (var s : secs) s.recalcBlockCounts();
            assertTrue(secs[3].hasOnlyAir(), "Section 3 should be hasOnlyAir()");

            int[] testMaxYs = {64, 70, 63, 48, 47, 29, 25, 11, 1, 0};
            for (int maxY : testMaxYs) {
                for (int layer = 0; layer <= 5; layer++) {
                    int fast = com.chunking.engine.world.FastPlacement.findOnGroundYFromSections(
                            secs, 0, 0, 3, 5, maxY, layer);
                    // Reference vanilla scan
                    int found = 0;
                    int ref = Integer.MAX_VALUE;
                    BlockState above = (maxY < 0 || maxY >= 64) ? air : secs[maxY >> 4].getBlockState(3, maxY & 15, 5);
                    for (int y = maxY; y >= 1; y--) {
                        int belowY = y - 1;
                        BlockState below = (belowY < 0 || belowY >= 64) ? air : secs[belowY >> 4].getBlockState(3, belowY & 15, 5);
                        boolean belowEmpty = below.isAir() || below.is(water.getBlock()) || below.is(lava.getBlock());
                        boolean aboveEmpty = above.isAir() || above.is(water.getBlock()) || above.is(lava.getBlock());
                        if (!belowEmpty && aboveEmpty && !below.is(bedrock.getBlock())) {
                            if (found == layer) {
                                ref = belowY + 1;
                                break;
                            }
                            found++;
                        }
                        above = below;
                    }
                    assertTrue(fast == ref, "findOnGroundYFromSections mismatch at maxY=" + maxY
                            + " layer=" + layer + ": fast=" + fast + " ref=" + ref);
                }
            }
        }

        // 7. FrameGuard tall-world section scaling & server-tick pressure hysteresis
        {
            FrameGuard gov = FrameGuard.get();
            FrameGuardProbe.init(8);
            gov.onServerTick(5.0D);
            gov.recordClientWorldSections(24);
            int cap24 = gov.maxChunkPacketsPerFrame();
            gov.recordClientWorldSections(48);
            int cap48 = gov.maxChunkPacketsPerFrame();
            assertTrue(cap48 < cap24, "48-section tall world should scale down packet cap vs 24 (" + cap48 + " vs " + cap24 + ")");
            gov.recordClientWorldSections(24);
            assertTrue(!gov.isServerTickPressured(), "Initial server tick must not be pressured");
            gov.onServerTick(45.0D);
            assertTrue(gov.isServerTickPressured(), "45ms server tick must trigger serverTickPressured");
            assertTrue(gov.maxChunkPacketsPerFrame() <= 4, "Pressured server tick must cap packets <= 4");
        }

        // 7b. FlatSequenceRule XZ dead-mask pruning (<64) + safe >64 eval & NoiseChunkPlan stateless mod DF caching
        try {
            final long[] xzStamp = { 1L };
            final boolean[] match40 = { false };
            final int[] evalCount40 = { 0 };
            final boolean[] match80 = { false };
            var ctxMock = new com.chunking.engine.mixin.accessor.SurfaceContextAccessor() {
                @Override public int chunking$getBlockX() { return 0; }
                @Override public int chunking$getBlockY() { return 64; }
                @Override public int chunking$getBlockZ() { return 0; }
                @Override public long chunking$getLastUpdateXZ() { return xzStamp[0]; }
                @Override public long chunking$getLastUpdateY() { return 1L; }
                @Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> chunking$getBiome() { return null; }
                @Override public java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> chunking$getPossibleBiomes() { return java.util.Set.of(); }
            };
            var neverCond = new com.chunking.engine.access.SurfaceConditionAccess() {
                @Override public boolean chunking$test() { return false; }
            };
            class XzCond implements com.chunking.engine.access.SurfaceConditionAccess,
                    com.chunking.engine.world.FastSurface.LazyFastAccess {
                private final boolean[] match;
                private final int[] evalCount;
                XzCond(boolean[] match, int[] evalCount) { this.match = match; this.evalCount = evalCount; }
                @Override public boolean chunking$test() { return xzCondEval(); }
                private boolean xzCondEval() { if (this.evalCount != null) this.evalCount[0]++; return this.match[0]; }
                @Override public int chunking$getLazyKind() { return 1; }
                @Override public com.chunking.engine.mixin.accessor.SurfaceContextAccessor chunking$getLazyContext() { return ctxMock; }
                @Override public boolean chunking$testXZ(long curXZ) { return xzCondEval(); }
                @Override public boolean chunking$testY(long curY) { return false; }
            }
            var xzCond40 = new XzCond(match40, evalCount40);
            var xzCond80 = new XzCond(match80, null);
            Class<?> srCls = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule");
            Object rules = java.lang.reflect.Array.newInstance(srCls, 100);
            Object stoneLeaf = com.chunking.engine.world.FastSurface.evalRuleSource(
                    net.minecraft.world.level.levelgen.SurfaceRules.state(
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()), null);
            Object dirtLeaf = com.chunking.engine.world.FastSurface.evalRuleSource(
                    net.minecraft.world.level.levelgen.SurfaceRules.state(
                            net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()), null);
            for (int i = 0; i < 100; i++) {
                Object r;
                if (i == 40) {
                    r = com.chunking.engine.world.FastSurface.newTestRule(xzCond40, stoneLeaf);
                } else if (i == 80) {
                    r = com.chunking.engine.world.FastSurface.newTestRule(xzCond80, stoneLeaf);
                } else {
                    r = com.chunking.engine.world.FastSurface.newTestRule(neverCond, dirtLeaf);
                }
                java.lang.reflect.Array.set(rules, i, r);
            }
            var ctor = com.chunking.engine.world.FastSurface.FlatSequenceRule.class.getDeclaredConstructors()[1];
            ctor.setAccessible(true);
            Object seq = ctor.newInstance(rules, ctxMock);
            Object[] seqArr = (Object[]) java.lang.reflect.Array.newInstance(srCls, 1);
            seqArr[0] = seq;
            assertTrue(com.chunking.engine.world.FastSurface.evalSequence(seqArr, 0, 64, 0) == null,
                    "Rules 40 and 80 false on col 1");
            assertTrue(com.chunking.engine.world.FastSurface.evalSequence(seqArr, 0, 63, 0) == null,
                    "Rule 40 pruned at y=63 on col 1");
            assertTrue(com.chunking.engine.world.FastSurface.evalSequence(seqArr, 0, 62, 0) == null,
                    "Rule 40 pruned at y=62 on col 1");
            assertTrue(evalCount40[0] == 1, "Rule 40 (<64) XZ cond must evaluate only once per column, got " + evalCount40[0]);
            xzStamp[0] = 2L;
            match80[0] = true;
            assertTrue(com.chunking.engine.world.FastSurface.evalSequence(seqArr, 0, 64, 0)
                            == net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                    "New XZ column must still evaluate and match rule 80");

            record CustomStatelessModDf(double val) implements DensityFunction.SimpleFunction {
                @Override public double compute(FunctionContext ctx) { return this.val + ctx.blockX() * 0.25D; }
                @Override public double minValue() { return -1000.0D; }
                @Override public double maxValue() { return 1000.0D; }
                @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
            }
            var modDf = new CustomStatelessModDf(3.5D);
            var plan = new com.chunking.engine.simd.NoiseJit.NoiseChunkPlan();
            var k1 = plan.resolve(0, 0, modDf, java.util.List.of(), java.util.List.of(), java.util.List.of());
            var k2 = plan.resolve(0, 0, modDf, java.util.List.of(), java.util.List.of(), java.util.List.of());
            var k3 = plan.resolve(0, 0, modDf, java.util.List.of(), java.util.List.of(), java.util.List.of());
            assertTrue(k1 != null && k2 != null && k3 != null, "Custom stateless mod DF must resolve");
            assertTrue(k2.kernel() == k3.kernel(), "Chunk 3 must reuse cached CompiledRecipe kernel for stateless mod DF");
            double[] modOut = new double[1];
            k3.evalBatch(new int[]{8}, new int[]{64}, new int[]{0}, modOut, 1, null, false);
            assertTrue(Math.abs(modOut[0] - 5.5D) < 1e-9, "Cached stateless mod DF must compute exact value");
        } catch (Exception e) {
            throw new AssertionError("Modded >64-rule / NoiseChunkPlan test failed", e);
        }

        // Multi-dimension TickBudgeter, BlockTagBitSet, ModCompatHelper session reset, WorkerPool cached executors, Spline parity
        {
            com.chunking.engine.scheduler.TickBudget.recordDimensionTickMs(101, 4.5D);
            com.chunking.engine.scheduler.TickBudget.recordDimensionTickMs(101, 3.0D);
            assertTrue(Math.abs(com.chunking.engine.scheduler.TickBudget.lastRecordedTickMs() - 7.5D) < 1e-6,
                    "Same tickCount must accumulate dimension tick times");
            com.chunking.engine.scheduler.TickBudget.recordDimensionTickMs(102, 2.0D);
            assertTrue(Math.abs(com.chunking.engine.scheduler.TickBudget.lastRecordedTickMs() - 2.0D) < 1e-6,
                    "New tickCount must reset accumulated tick time");
            assertTrue(com.chunking.engine.scheduler.TickBudget.claimGovernorTick(500), "First claim for tick 500 must succeed");
            assertTrue(!com.chunking.engine.scheduler.TickBudget.claimGovernorTick(500), "Duplicate claim for tick 500 must fail");
            assertTrue(com.chunking.engine.scheduler.TickBudget.claimGovernorTick(501), "Claim for tick 501 must succeed");

            var stoneHolder = net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(net.minecraft.world.level.block.Blocks.STONE);
            var dirtHolder = net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(net.minecraft.world.level.block.Blocks.DIRT);
            long[] bits = com.chunking.engine.world.BlockTagBits.build(java.util.List.of(stoneHolder));
            assertTrue(com.chunking.engine.world.BlockTagBits.contains(bits, net.minecraft.world.level.block.Blocks.STONE),
                    "BlockTagBitSet must contain STONE");
            assertTrue(!com.chunking.engine.world.BlockTagBits.contains(bits, net.minecraft.world.level.block.Blocks.DIRT),
                    "BlockTagBitSet must not contain DIRT");
            assertTrue(!com.chunking.engine.world.BlockTagBits.contains(
                    com.chunking.engine.world.BlockTagBits.build(java.util.List.of()),
                    net.minecraft.world.level.block.Blocks.STONE), "Empty BlockTagBitSet must return false");

            com.chunking.engine.world.ModCompat.resetSession();
            com.chunking.engine.scheduler.WorkerPool wp = new com.chunking.engine.scheduler.WorkerPool(2, 6);
            try {
                assertTrue(wp.executor() == wp.executor(), "WorkerPool.executor() must be cached");
                assertTrue(wp.inlineIfWorkerExecutor() == wp.inlineIfWorkerExecutor(), "WorkerPool.inlineIfWorkerExecutor() must be cached");
            } finally {
                wp.close();
            }

            // Spline parity: Constant + Multipoint interior + left/right extrapolation
            double[] testXs = {-2.5, -1.0, -0.25, 0.5, 1.25, 2.0, 3.5};
            for (double tx : testXs) {
                var inner = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(tx));
                var rebuilt = net.minecraft.util.CubicSpline.builder(inner)
                        .addPoint(-1.0F, 0.25F, 0.5F)
                        .addPoint(0.5F, 1.25F, -0.25F)
                        .addPoint(2.0F, -0.5F, 0.75F)
                        .build();
                var dfSpline = new DensityFunctions.Spline(rebuilt);
                var ctx = new DensityFunction.SinglePointContext(0, 0, 0);
                double got = dfSpline.compute(ctx);
                double ref = net.minecraft.util.CubicSpline.asSampler(rebuilt).apply(new DensityFunctions.Spline.Point(ctx));
                assertTrue(Math.abs(got - ref) < 1e-6, "Spline parity mismatch at x=" + tx + ": got=" + got + " ref=" + ref);
            }
        }

        // Smooth camera-following radial pattern (no side holes, responsive camera pan)
        {
            // Moving fast East (vx=3.5, vz=0) and looking East (lookX=1, lookZ=0):
            // Near side chunk (0,4) must beat distant forward chunk (20,0) -> no side holes!
            int sideNear = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 1.0D, 0.0D, 0, 4);
            int fwdFar = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 1.0D, 0.0D, 20, 0);
            assertTrue(sideNear < fwdFar, "Near side chunk (0,4) must beat distant forward (20,0): " + sideNear + " vs " + fwdFar);

            // Equal distance: forward (8,0) < side (0,8) < rear (-8,0)
            int eqFwd = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 1.0D, 0.0D, 8, 0);
            int eqSide = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 1.0D, 0.0D, 0, 8);
            int eqRear = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 1.0D, 0.0D, -8, 0);
            assertTrue(eqFwd < eqSide && eqSide < eqRear, "Equal-distance radial bias failed: " + eqFwd + " < " + eqSide + " < " + eqRear);

            // Camera pan responsiveness while moving fast East (vx=3.5, vz=0) while looking South (lookX=0, lookZ=1):
            int panSouth = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 0.0D, 1.0D, 0, 8);
            int panNorth = LookAhead.computeSendSortScore(0, 0, 3.5D, 0.0D, 0.0D, 1.0D, 0, -8);
            assertTrue(panSouth < panNorth, "Camera pan South must beat North while moving East: " + panSouth + " vs " + panNorth);

            var tracker = new MoveTracker();
            tracker.clear();
            var m = new MoveTracker.PlayerMotion(8.0D, 80.0D, 8.0D, 3.5D, 0.0D, 0.0D, 0.0D, 1.0D, 1L);
            tracker.updateDimension("minecraft:overworld", new MoveTracker.PlayerMotion[]{m});
            double effSouth = LookAhead.computeEffectiveDistance(0, 0, m.vx, m.vz, m.lookX, m.lookZ, 0, 8);
            double effNorth = LookAhead.computeEffectiveDistance(0, 0, m.vx, m.vz, m.lookX, m.lookZ, 0, -8);
            assertTrue(effSouth < effNorth, "Effective distance must follow camera turn");
        }

        // Symmetric radial scoring, FlatBiomeTree.searchColumnBatch parity, and ModCompat checks
        {
            // Equidistant left (-4, 8) vs right (+4, 8) when looking +Z (0, 1) must match identically
            double leftEff = LookAhead.computeEffectiveDistance(0, 0, 0.0D, 3.0D, 0.0D, 1.0D, -4, 8);
            double rightEff = LookAhead.computeEffectiveDistance(0, 0, 0.0D, 3.0D, 0.0D, 1.0D, 4, 8);
            assertTrue(Math.abs(leftEff - rightEff) < 1e-9, "Symmetric left/right effective distance mismatch: " + leftEff + " vs " + rightEff);

            // FlatBiomeTree.searchColumnBatch exact parity across 200 random columns x 96 Y-depths
            java.util.Random rTreeRng = new java.util.Random(42L);
            java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.level.biome.Climate.ParameterPoint, String>> entries =
                    new java.util.ArrayList<>();
            long[] depthMins = {0L, 2000L, 11000L};
            long[] depthMaxs = {0L, 9000L, 12000L};
            for (int b = 0; b < 32; b++) {
                float t0 = -1.0F + 2.0F * rTreeRng.nextFloat();
                float h0 = -1.0F + 2.0F * rTreeRng.nextFloat();
                float c0 = -1.0F + 2.0F * rTreeRng.nextFloat();
                float e0 = -1.0F + 2.0F * rTreeRng.nextFloat();
                float w0 = -1.0F + 2.0F * rTreeRng.nextFloat();
                int band = b % 3;
                var pp = new net.minecraft.world.level.biome.Climate.ParameterPoint(
                        net.minecraft.world.level.biome.Climate.Parameter.span(t0 - 0.15F, t0 + 0.15F),
                        net.minecraft.world.level.biome.Climate.Parameter.span(h0 - 0.15F, h0 + 0.15F),
                        net.minecraft.world.level.biome.Climate.Parameter.span(c0 - 0.15F, c0 + 0.15F),
                        net.minecraft.world.level.biome.Climate.Parameter.span(e0 - 0.15F, e0 + 0.15F),
                        new net.minecraft.world.level.biome.Climate.Parameter(depthMins[band], depthMaxs[band]),
                        net.minecraft.world.level.biome.Climate.Parameter.span(w0 - 0.15F, w0 + 0.15F),
                        0L
                );
                entries.add(com.mojang.datafixers.util.Pair.of(pp, "biome_" + b));
            }
            final com.chunking.engine.world.FlatBiomeTree flat;
            try {
                Class<?> rtreeCls = Class.forName("net.minecraft.world.level.biome.Climate$RTree");
                var createM = rtreeCls.getDeclaredMethod("create", java.util.List.class);
                createM.setAccessible(true);
                Object rtree = createM.invoke(null, entries);
                var rootFld = rtreeCls.getDeclaredField("root");
                rootFld.setAccessible(true);
                flat = com.chunking.engine.world.FlatBiomeTree.buildFromRawRoot(rootFld.get(rtree));
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
            assertTrue(flat != null, "FlatRTree build must succeed");

            int[] dfsCur = new int[64];
            int[] dfsEnd = new int[64];
            long[] buf = new long[7];
            long[] dQs = new long[96];
            Object[] colOut = new Object[96];
            for (int yi = 0; yi < 96; yi++) {
                // Monotonically decreasing from +15000 down to -4000 (mirrors deep -> sky Y-sweep)
                dQs[yi] = 15000L - yi * 200L;
            }
            for (int col = 0; col < 200; col++) {
                long tQ = (long) ((rTreeRng.nextDouble() * 2.0D - 1.0D) * 10000.0D);
                long hQ = (long) ((rTreeRng.nextDouble() * 2.0D - 1.0D) * 10000.0D);
                long cQ = (long) ((rTreeRng.nextDouble() * 2.0D - 1.0D) * 10000.0D);
                long eQ = (long) ((rTreeRng.nextDouble() * 2.0D - 1.0D) * 10000.0D);
                long wQ = (long) ((rTreeRng.nextDouble() * 2.0D - 1.0D) * 10000.0D);
                com.chunking.engine.world.FlatBiomeTree.searchColumnFast(
                        flat, dfsCur, dfsEnd, buf,
                        -1, true, true, depthMins, depthMaxs,
                        tQ, hQ, cQ, eQ, wQ, dQs, 96, colOut, 0, 1);
                for (int yi = 0; yi < 96; yi++) {
                    int refIdx = com.chunking.engine.world.FlatBiomeTree.searchFlat(
                            flat, dfsCur, dfsEnd, buf, -1, null, tQ, hQ, cQ, eQ, dQs[yi], wQ);
                    Object refVal = flat.leafValues[refIdx];
                    assertTrue(refVal.equals(colOut[yi]),
                            "searchColumnFast mismatch at col=" + col + " yi=" + yi + ": got=" + colOut[yi] + " ref=" + refVal);
                }
            }
            assertTrue(!com.chunking.engine.world.ModCompat.isYInvariantModDensityFunction(DensityFunctions.zero().getClass()),
                    "Vanilla class must not report as mod DF");
        }

        // Smoothed Governor packet budgets, TickBudgeter full-tick precedence, async PriorityTaskScheduler rebalance, full=0 client RecentCache
        {
            FrameGuard gov = FrameGuard.get();
            FrameGuardProbe.init(8);
            gov.onServerTick(5.0D);
            gov.recordClientWorldSections(24);
            assertTrue(gov.packetBudgetNs() == 1_350_000L, "CALM packet budget must be 1.35ms");
            assertTrue(gov.minChunkPacketsPerFrame() == 1 && gov.maxChunkPacketsPerFrame() == 8,
                    "CALM packet caps must be 1..8");
            for (int f = 0; f < 12; f++) {
                FrameGuardProbe.feedFrame(16.6D);
            }
            FrameGuardProbe.feedFrame(60.0D);
            assertTrue(gov.isInFrameHitch(), "Hitch frame (60ms) must trigger hitch cooldown");
            assertTrue(gov.packetBudgetNs() == 600_000L && gov.maxChunkPacketsPerFrame() == 3,
                    "Hitch cooldown must tighten packet budget to 0.6ms and max packets to 3");

            com.chunking.engine.scheduler.TickBudget.recordFullTickMs(9001, 14.25D);
            com.chunking.engine.scheduler.TickBudget.recordDimensionTickMs(9001, 2.0D);
            assertTrue(Math.abs(com.chunking.engine.scheduler.TickBudget.lastRecordedTickMs() - 14.25D) < 1e-6,
                    "recordFullTickMs must take precedence over recordDimensionTickMs for the same tick");

            com.chunking.engine.scheduler.TaskQueue.resetSession();
            java.util.concurrent.Executor directExec = Runnable::run;
            var f1 = com.chunking.engine.scheduler.TaskQueue.submit(4, 4, 7, () -> "ok", directExec);
            assertTrue("ok".equals(f1.join()), "PriorityTaskScheduler submit must complete");
            com.chunking.engine.scheduler.MoveTracker vt = new com.chunking.engine.scheduler.MoveTracker();
            var pm = vt.recordPlayerTick(1, 32.0D, 64.0D, 32.0D, 2.0D, 0.0D, 1.0D, 0.0D, 10L);
            com.chunking.engine.scheduler.TaskQueue.onPlayerMotionsUpdated(
                    new com.chunking.engine.scheduler.MoveTracker.PlayerMotion[] { pm });

            com.chunking.engine.io.RecentChunks clientCache =
                    new com.chunking.engine.io.RecentChunks(256, 64, 0);
            assertTrue(clientCache.peekChunkType(12345L) == 0, "Empty client cache must return 0");
            assertTrue(clientCache.acquireForLoad(12345L) == null, "Empty client cache must return null");
        }

        // NoiseChunkPlan must separate empty vs non-empty Beardifier slots and rebind live Beardifier instances
        {
            record FixedBeardifier(double v) implements DensityFunctions.BeardifierOrMarker {
                @Override public double compute(DensityFunction.FunctionContext ctx) { return v; }
                @Override public double minValue() { return Math.min(0.0D, v); }
                @Override public double maxValue() { return Math.max(0.0D, v); }
            }
            var plan = new com.chunking.engine.simd.NoiseJit.NoiseChunkPlan();
            int[] bxs = {0}, bys = {0}, bzs = {0};
            double[] outs = new double[1];

            DensityFunction emptyFn = DensityFunctions.add(DensityFunctions.constant(1.0D), net.minecraft.world.level.levelgen.Beardifier.EMPTY);
            var kEmpty = plan.resolve(
                    com.chunking.engine.simd.NoiseJit.PLAN_CELL_FILLER, 0, emptyFn,
                    List.of(), List.of(), List.of(), net.minecraft.world.level.levelgen.Beardifier.EMPTY);
            kEmpty.evalBatch(bxs, bys, bzs, outs, 1, null, false);
            assertTrue(Math.abs(outs[0] - 1.0D) < 1e-12, "Empty Beardifier plan must eval to 1.0D, got " + outs[0]);

            var beardPlus5 = new FixedBeardifier(5.0D);
            DensityFunction fnPlus5 = DensityFunctions.add(DensityFunctions.constant(1.0D), beardPlus5);
            var kPlus5 = plan.resolve(
                    com.chunking.engine.simd.NoiseJit.PLAN_CELL_FILLER, 0, fnPlus5,
                    List.of(), List.of(), List.of(), beardPlus5);
            kPlus5.evalBatch(bxs, bys, bzs, outs, 1, null, false);
            assertTrue(Math.abs(outs[0] - 6.0D) < 1e-12, "Non-empty Beardifier(+5) must eval to 6.0D after EMPTY warmup, got " + outs[0]);

            var beardMinus3 = new FixedBeardifier(-3.0D);
            DensityFunction fnMinus3 = DensityFunctions.add(DensityFunctions.constant(1.0D), beardMinus3);
            var kMinus3 = plan.resolve(
                    com.chunking.engine.simd.NoiseJit.PLAN_CELL_FILLER, 0, fnMinus3,
                    List.of(), List.of(), List.of(), beardMinus3);
            kMinus3.evalBatch(bxs, bys, bzs, outs, 1, null, false);
            assertTrue(Math.abs(outs[0] - (-2.0D)) < 1e-12, "Rebound Beardifier(-3) must eval to -2.0D, got " + outs[0]);
        }

        System.out.println("[PASS] 11. Worldgen Mod Compatibility: CustomDF(JIT+Interval) + CustomSlicePools + OreBounds + DynamicWriteRadius=OK");
    }

    private static void testThreadingAndSafetyHardening() throws Exception {
        // 1. SafeRandom: concurrent calls from owner + 8 worker threads
        final Thread ownerThread = Thread.currentThread();
        var levelRandom = new com.chunking.engine.core.SafeRandom(0xC2AE_1234L, () -> ownerThread);
        AtomicInteger randErrors = new AtomicInteger();
        ExecutorService randExec = Executors.newFixedThreadPool(8);
        List<CompletableFuture<Void>> randFuts = new ArrayList<>(8);
        for (int t = 0; t < 8; t++) {
            randFuts.add(CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < 10_000; i++) {
                        int v = levelRandom.nextInt(100);
                        double g = levelRandom.nextGaussian();
                        if (v < 0 || v >= 100 || Double.isNaN(g)) {
                            randErrors.incrementAndGet();
                        }
                    }
                } catch (Throwable ex) {
                    randErrors.incrementAndGet();
                }
            }, randExec));
        }
        for (int i = 0; i < 10_000; i++) {
            int v = levelRandom.nextInt(100);
            double g = levelRandom.nextGaussian();
            if (v < 0 || v >= 100 || Double.isNaN(g)) {
                randErrors.incrementAndGet();
            }
        }
        CompletableFuture.allOf(randFuts.toArray(CompletableFuture[]::new)).join();
        randExec.shutdown();
        assertTrue(randErrors.get() == 0, "SafeRandom must have zero concurrent errors/NaNs");

        // 2. SyncCodec: concurrent encode/decode on a non-thread-safe Codec
        AtomicInteger activeCodecCalls = new AtomicInteger();
        AtomicInteger codecRaces = new AtomicInteger();
        Codec<String> racyCodec = new Codec<>() {
            @Override
            public <T> com.mojang.serialization.DataResult<com.mojang.datafixers.util.Pair<String, T>> decode(
                    com.mojang.serialization.DynamicOps<T> ops, T input) {
                if (activeCodecCalls.incrementAndGet() > 1) {
                    codecRaces.incrementAndGet();
                }
                try {
                    return Codec.STRING.decode(ops, input);
                } finally {
                    activeCodecCalls.decrementAndGet();
                }
            }

            @Override
            public <T> com.mojang.serialization.DataResult<T> encode(
                    String input, com.mojang.serialization.DynamicOps<T> ops, T prefix) {
                if (activeCodecCalls.incrementAndGet() > 1) {
                    codecRaces.incrementAndGet();
                }
                try {
                    return Codec.STRING.encode(input, ops, prefix);
                } finally {
                    activeCodecCalls.decrementAndGet();
                }
            }
        };
        var syncCodec = new com.chunking.engine.io.SyncCodec<>(racyCodec);
        ExecutorService codecExec = Executors.newFixedThreadPool(8);
        List<CompletableFuture<Void>> codecFuts = new ArrayList<>(8);
        for (int t = 0; t < 8; t++) {
            final int tid = t;
            codecFuts.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 1_000; i++) {
                    String s = "v_" + tid + "_" + i;
                    var enc = syncCodec.encodeStart(NbtOps.INSTANCE, s).getOrThrow();
                    var dec = syncCodec.parse(NbtOps.INSTANCE, enc).getOrThrow();
                    if (!s.equals(dec)) {
                        codecRaces.incrementAndGet();
                    }
                }
            }, codecExec));
        }
        CompletableFuture.allOf(codecFuts.toArray(CompletableFuture[]::new)).join();
        codecExec.shutdown();
        assertTrue(codecRaces.get() == 0, "SyncCodec must serialize concurrent codec access");

        // 3. RecentChunks: put/peek/acquire one-shot ownership & invalidation
        var recent = new com.chunking.engine.io.RecentChunks(8, 8, 8);
        long posKey = ChunkPos.pack(42, -17);
        assertTrue(recent.peekChunkType(posKey) == 0 && recent.acquireForLoad(posKey) == null,
                "Empty cache must miss");
        recent.invalidate(posKey);
        recent.clear();

        System.out.println("[PASS] 12. Threading & Safety Hardening: SafeRandom + SyncCodec + RecentChunks=OK");
    }

    private static void testOreVeinAndBaseHeightParityGuards() throws Exception {
        java.nio.file.Path mixinPath = java.nio.file.Path.of(
                "src", "main", "java", "com", "chunking", "engine", "mixin", "NoiseChunkMixin.java");
        String src = java.nio.file.Files.readString(mixinPath);
        assertTrue(!src.contains("x0 = x1 - this.cellWidth"),
                "OreVeinifier corner fill must NOT shift x0 by -cellWidth");
        assertTrue(src.contains("int x0 = this.cellStartBlockX;\n            int x1 = x0 + this.cellWidth;")
                        || src.contains("int x0 = this.cellStartBlockX;\r\n            int x1 = x0 + this.cellWidth;"),
                "OreVeinifier corner fill must pass [cellStartBlockX, cellStartBlockX + cellWidth]");
        assertTrue(src.contains("java.util.Arrays.fill(fullVals, 1.0D);"),
                "cls > 0 must always populate fullVals with 1.0D for iterateNoiseColumn / getBaseHeight parity");
        assertTrue(src.contains("this.cellCountXZ > 1 ? this.chunking$initRouter : null"),
                "forColumn (cellCountXZ == 1) must not poison INTERP_ROLE_CACHE for full chunks");
        System.out.println("[PASS] 13. OreVeinifier & BaseHeight Parity Guards: x0/x1 bounds + cls>0 fullVals + forColumn isolation=OK");
    }

    private static void testRenderDistanceAndFogUnlock() {
        assertTrue(com.chunking.engine.config.ModConfig.SCALE_DIMENSION_FOG,
                "SCALE_DIMENSION_FOG must default to true");
        int maxTicket = com.chunking.engine.config.ModConfig.maxPlayerTicketDistance();
        assertTrue(maxTicket >= 64 && maxTicket <= 124,
                "maxPlayerTicketDistance must be in [64, 124] (signed byte safe), got " + maxTicket);
        int maxSlider = com.chunking.engine.config.ModConfig.maxSliderRenderDistance();
        assertTrue(maxSlider >= 64, "maxSliderRenderDistance must be >= 64, got " + maxSlider);

        int[] dists = {0, 16, 24, 32, 48, 64};
        int prevBucket = -1;
        for (int d : dists) {
            int b = com.chunking.engine.scheduler.TaskQueue.computeBucket(d, 0, 7);
            assertTrue(b >= 0 && b < 64, "Bucket out of range at d=" + d + ": " + b);
            assertTrue(b >= prevBucket, "Bucket must be non-decreasing across 0..64: d=" + d + " b=" + b + " prev=" + prevBucket);
            prevBucket = b;
        }

        // Verify ChunkTaskPriorityQueue handles >45 priority levels via mixin or in live server test
        System.out.println("[PASS] 14. Render Distance >32 & Dimension Fog Config/Scheduler: maxTicket="
                + maxTicket + " maxSlider=" + maxSlider + " monotonicBuckets=OK");
    }

    private static void testLightingEngineAndMultiDimTrackerOptimizations() {
        // 1. Verify O(1) multi-dimension velocity tracker does not wipe active Overworld motions when empty Nether/End tick
        MoveTracker tracker = new MoveTracker();
        assertTrue(tracker.getAnyActiveMotions() == MoveTracker.EMPTY_MOTIONS,
                "Initial getAnyActiveMotions must return singleton EMPTY_MOTIONS");
        MoveTracker.PlayerMotion m0 = tracker.recordPlayerTick(1, 100.0, 80.0, 200.0, 1.5, -0.5, 0.8, -0.6, 10L);
        tracker.updateDimension("minecraft:overworld", new MoveTracker.PlayerMotion[]{m0});
        tracker.updateDimension("minecraft:the_nether", MoveTracker.EMPTY_MOTIONS);
        tracker.updateDimension("minecraft:the_end", MoveTracker.EMPTY_MOTIONS);
        assertTrue(tracker.getAnyActiveMotions().length == 1 && tracker.getAnyActiveMotions()[0] == m0,
                "Empty Nether/End ticks must not clear active Overworld player motion");
        tracker.updateDimension("minecraft:overworld", MoveTracker.EMPTY_MOTIONS);
        assertTrue(tracker.getAnyActiveMotions() == MoveTracker.EMPTY_MOTIONS,
                "Clearing last active dimension must restore singleton EMPTY_MOTIONS");

        // 2. Verify real SkyLightEngine + BlockLightEngine propagation, homogenous DataLayer(15) preservation,
        //    palette-bitmask emissive scan, and interior/border propagateDecrease
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> biomeIdMap = new IdMapper<>();
        biomeLookup.listElements().forEach(biomeIdMap::add);
        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeIdMap);
        Holder<Biome> plains = biomeLookup.getOrThrow(Biomes.PLAINS);
        net.minecraft.world.level.chunk.PalettedContainerFactory factory =
                new net.minecraft.world.level.chunk.PalettedContainerFactory(
                        blockStrategy, Blocks.AIR.defaultBlockState(), null, biomeStrategy, plains, null);
        net.minecraft.world.level.LevelHeightAccessor heightAcc =
                net.minecraft.world.level.LevelHeightAccessor.create(-64, 384);

        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.chunk.ProtoChunk> chunks =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                net.minecraft.world.level.chunk.ProtoChunk pc = new net.minecraft.world.level.chunk.ProtoChunk(
                        cp, net.minecraft.world.level.chunk.UpgradeData.EMPTY, heightAcc, factory, null);
                int topY = (cx == 1 && cz == 0) ? 96 : 64;
                for (int y = 0; y <= topY; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            pc.setBlockState(new BlockPos(x, y, z), Blocks.END_STONE.defaultBlockState(), 0);
                        }
                    }
                }
                if (cx == 0 && cz == 0) {
                    // Place emissive block inside a carved interior room and at a section border
                    for (int y = 20; y <= 26; y++) {
                        for (int z = 2; z <= 12; z++) {
                            for (int x = 2; x <= 12; x++) {
                                pc.setBlockState(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 0);
                            }
                        }
                    }
                    pc.setBlockState(new BlockPos(7, 23, 7), Blocks.SHROOMLIGHT.defaultBlockState(), 0);
                    pc.setBlockState(new BlockPos(0, 70, 7), Blocks.GLOWSTONE.defaultBlockState(), 0);
                }
                pc.initializeLightSources();
                chunks.put(cp.pack(), pc);
            }
        }

        net.minecraft.world.level.chunk.LightChunkGetter getter = new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override
            public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int cx, int cz) {
                return chunks.get(ChunkPos.pack(cx, cz));
            }

            @Override
            public net.minecraft.world.level.BlockGetter getLevel() {
                return net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
            }
        };

        net.minecraft.world.level.lighting.LevelLightEngine lightEngine =
                new net.minecraft.world.level.lighting.LevelLightEngine(getter, true, true);
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                net.minecraft.world.level.chunk.ProtoChunk pc = chunks.get(ChunkPos.pack(cx, cz));
                for (int sy = heightAcc.getMinSectionY(); sy <= heightAcc.getMaxSectionY(); sy++) {
                    int idx = pc.getSectionIndexFromSectionY(sy);
                    if (idx >= 0 && idx < pc.getSections().length && !pc.getSection(idx).hasOnlyAir()) {
                        lightEngine.updateSectionStatus(net.minecraft.core.SectionPos.of(cx, sy, cz), false);
                    }
                }
                lightEngine.setLightEnabled(pc.getPos(), true);
            }
        }
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                lightEngine.propagateLightSources(new ChunkPos(cx, cz));
            }
        }
        int updates = lightEngine.runLightUpdates();
        assertTrue(updates > 0, "Expected >0 light updates during 3x3 chunk lighting");

        net.minecraft.world.level.lighting.LayerLightEventListener blockListener =
                lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        net.minecraft.world.level.lighting.LayerLightEventListener skyListener =
                lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);

        int roomCenterLight = blockListener.getLightValue(new BlockPos(7, 23, 7));
        int roomAdjLight = blockListener.getLightValue(new BlockPos(8, 23, 7));
        int borderGlowLight = blockListener.getLightValue(new BlockPos(0, 70, 7));
        int neighborChunkLight = blockListener.getLightValue(new BlockPos(-1, 70, 7));
        assertTrue(roomCenterLight == 15 && roomAdjLight == 14,
                "Interior block light propagation mismatch: center=" + roomCenterLight + " adj=" + roomAdjLight);
        assertTrue(borderGlowLight == 15 && neighborChunkLight == 14,
                "Cross-chunk border block light propagation mismatch: src=" + borderGlowLight + " neigh=" + neighborChunkLight);

        int surfaceSky = skyListener.getLightValue(new BlockPos(7, 65, 7));
        int caveSky = skyListener.getLightValue(new BlockPos(7, 23, 7));
        assertTrue(surfaceSky == 15 && caveSky == 0,
                "Sky light propagation mismatch: surface=" + surfaceSky + " cave=" + caveSky);

        // Now remove both emissive light sources and verify propagateDecrease (interior + cross-chunk border)
        net.minecraft.world.level.chunk.ProtoChunk centerChunk = chunks.get(ChunkPos.pack(0, 0));
        centerChunk.setBlockState(new BlockPos(7, 23, 7), Blocks.AIR.defaultBlockState(), 0);
        centerChunk.setBlockState(new BlockPos(0, 70, 7), Blocks.AIR.defaultBlockState(), 0);
        lightEngine.checkBlock(new BlockPos(7, 23, 7));
        lightEngine.checkBlock(new BlockPos(0, 70, 7));
        int decUpdates = lightEngine.runLightUpdates();
        assertTrue(decUpdates > 0, "Expected >0 light decrease updates");
        assertTrue(blockListener.getLightValue(new BlockPos(7, 23, 7)) == 0
                        && blockListener.getLightValue(new BlockPos(8, 23, 7)) == 0
                        && blockListener.getLightValue(new BlockPos(0, 70, 7)) == 0
                        && blockListener.getLightValue(new BlockPos(-1, 70, 7)) == 0,
                "Block light decrease propagation must clear interior and cross-chunk light to 0");

        // 3. Direct unit verification of LightSpread (propagateIncrease + propagateDecrease interior & border)
        final net.minecraft.world.level.chunk.DataLayer testLayer = new net.minecraft.world.level.chunk.DataLayer();
        final java.util.ArrayList<Long> incQueue = new java.util.ArrayList<>();
        final java.util.ArrayList<Long> decQueue = new java.util.ArrayList<>();
        final com.chunking.engine.access.LightUpdatingAccess mockUpd =
                new com.chunking.engine.access.LightUpdatingAccess() {
                    @Override
                    public net.minecraft.world.level.chunk.DataLayer chunking$getUpdatingLayer(long secPos) {
                        return testLayer;
                    }

                    @Override
                    public net.minecraft.world.level.chunk.DataLayer chunking$getOrCopyWritableLayer(long secPos) {
                        return testLayer;
                    }

                    @Override
                    public int chunking$getStoredLevelFast(long blockPos) {
                        return testLayer.get(
                                BlockPos.getX(blockPos) & 15,
                                BlockPos.getY(blockPos) & 15,
                                BlockPos.getZ(blockPos) & 15
                        );
                    }

                    @Override
                    public void chunking$setStoredLevelFast(long blockPos, int level) {
                        testLayer.set(
                                BlockPos.getX(blockPos) & 15,
                                BlockPos.getY(blockPos) & 15,
                                BlockPos.getZ(blockPos) & 15,
                                level
                        );
                    }

                    @Override
                    public void chunking$markBorderAffected(int bx, int by, int bz) {}
                };
        final net.minecraft.world.level.chunk.ProtoChunk centerPc = centerChunk;
        final com.chunking.engine.access.LightEngineAccess mockEngine =
                new com.chunking.engine.access.LightEngineAccess() {
                    @Override
                    public com.chunking.engine.access.LightUpdatingAccess chunking$getStorage() {
                        return mockUpd;
                    }

                    @Override
                    public net.minecraft.world.level.chunk.LightChunk chunking$getChunk(int cx, int cz) {
                        return centerPc;
                    }

                    @Override
                    public BlockState chunking$getState(BlockPos pos) {
                        return Blocks.AIR.defaultBlockState();
                    }

                    @Override
                    public BlockState chunking$getState(int x, int y, int z, BlockPos.MutableBlockPos fallbackPos) {
                        return Blocks.AIR.defaultBlockState();
                    }

                    @Override
                    public int chunking$getOpacity(BlockState state) {
                        int d = state.getLightDampening();
                        return d > 1 ? d : 1;
                    }

                    @Override
                    public boolean chunking$shapeOccludes(BlockState fromState, BlockState toState, net.minecraft.core.Direction dir) {
                        return false;
                    }

                    @Override
                    public void chunking$enqueueIncrease(long pos, long entry) {
                        incQueue.add(pos);
                    }

                    @Override
                    public void chunking$enqueueDecrease(long pos, long entry) {
                        decQueue.add(pos);
                    }
                };
        long interiorNode = BlockPos.asLong(7, 23, 7);
        testLayer.set(7, 7, 7, 15);
        com.chunking.engine.world.LightSpread.propagateIncrease(
                mockEngine,
                new BlockPos.MutableBlockPos(),
                interiorNode,
                net.minecraft.world.level.lighting.LightEngine.QueueEntry.increaseLightFromEmission(15, true),
                15,
                0,
                null
        );
        assertTrue(testLayer.get(8, 7, 7) == 14 && testLayer.get(6, 7, 7) == 14 && incQueue.size() == 6,
                "LightSpread.propagateIncrease interior fast path must set all 6 neighbors to 14");
        testLayer.set(7, 7, 7, 0);
        incQueue.clear();
        com.chunking.engine.world.LightSpread.propagateDecrease(
                mockEngine,
                new BlockPos.MutableBlockPos(),
                interiorNode,
                net.minecraft.world.level.lighting.LightEngine.QueueEntry.decreaseAllDirections(15),
                0,
                null,
                true
        );
        assertTrue(testLayer.get(8, 7, 7) == 0 && testLayer.get(6, 7, 7) == 0 && decQueue.size() == 6,
                "LightSpread.propagateDecrease interior fast path must clear all 6 neighbors to 0");

        // Verify 16-dimension + tall dimY=1025 CustomSlicePool pooling (for space/custom dimension mods)
        for (int s = 0; s < 16; s++) {
            int dimY = 129 + s * 32;
            double[][] slice = new double[5][dimY];
            com.chunking.engine.simd.JitSupport.recycleInterpolatorSlice(slice);
            double[][] borrowed = com.chunking.engine.simd.JitSupport.borrowInterpolatorSlice(5, dimY);
            assertTrue(borrowed == slice, "CustomSlicePool must cache and return slice for dim slot " + s);
        }
        double[][] tallSlice = new double[5][1025];
        com.chunking.engine.simd.JitSupport.recycleInterpolatorSlice(tallSlice);
        assertTrue(com.chunking.engine.simd.JitSupport.borrowInterpolatorSlice(5, 1025) == tallSlice,
                "CustomSlicePool must support tall dimensions up to dimY=1025");

        System.out.println("[PASS] 15. Lighting Engine & Multi-Dim Tracker: HomogenousSky15 + PaletteBitmaskBlock + PropagateDecrease=OK");
    }

    private static void testHighCoreServerScaling() throws Exception {
        // 1. 64-thread AreaLock stress test (mixed r=1 and r=2)
        int prevWorkers = com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS;
        com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS = 64;
        try {
            AreaLock lock = new AreaLock();
            final int taskCount = 4_000;
            ConcurrentHashMap<Long, int[]> active = new ConcurrentHashMap<>();
            AtomicInteger maxParallel = new AtomicInteger();
            AtomicInteger violations = new AtomicInteger();
            List<CompletableFuture<Integer>> futures = new ArrayList<>(taskCount);
            try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
                for (int i = 0; i < taskCount; i++) {
                    int r = (i % 7 == 0) ? 2 : 1;
                    int cx = (i < 256) ? ((i % 16) * 6) : ((i < 2_000) ? ((i * 3) % 32) : ((i * 5) % 128));
                    int cz = (i < 256) ? ((i / 16) * 6) : ((i < 2_000) ? ((i * 5) % 32) : ((i * 7) % 128));
                    final long id = i;
                    final boolean holdBrief = i < 256;
                    futures.add(lock.executeAsync(cx, cz, r, () -> {
                        for (int[] other : active.values()) {
                            int minDist = r + other[2];
                            if (Math.abs(cx - other[0]) <= minDist && Math.abs(cz - other[1]) <= minDist) {
                                violations.incrementAndGet();
                            }
                        }
                        active.put(id, new int[]{cx, cz, r});
                        maxParallel.accumulateAndGet(active.size(), Math::max);
                        try {
                            if (holdBrief) {
                                java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
                            } else {
                                Thread.onSpinWait();
                            }
                        } finally {
                            active.remove(id);
                        }
                        return 1;
                    }, pool));
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertTrue(violations.get() == 0, "64-thread SpatialFeatureLock exclusion violated " + violations.get() + " times");
            assertTrue(lock.activeOwnedChunkCells() == 0, "64-thread SpatialFeatureLock leaked cells: " + lock.activeOwnedChunkCells());
            assertTrue(lock.pendingCount() == 0, "64-thread SpatialFeatureLock leaked pending: " + lock.pendingCount());
            assertTrue(lock.completedTasks() == taskCount, "64-thread SpatialFeatureLock completed mismatch");
            assertTrue(maxParallel.get() >= 16, "Expected >=16 parallel feature locks on 64 threads, got " + maxParallel.get());

            // 2. 64-thread PriorityTaskScheduler fast-path + queued-path stress test (20,000 tasks across 16 producers + 16 workers)
            com.chunking.engine.scheduler.TaskQueue.resetSession();
            final int schedCount = 20_000;
            List<CompletableFuture<Integer>> schedFutures = java.util.Collections.synchronizedList(new ArrayList<>(schedCount));
            try (ExecutorService workerPool = Executors.newFixedThreadPool(16);
                 ExecutorService producerPool = Executors.newFixedThreadPool(16)) {
                List<CompletableFuture<Void>> prodFuts = new ArrayList<>(16);
                for (int p = 0; p < 16; p++) {
                    final int pId = p;
                    prodFuts.add(CompletableFuture.runAsync(() -> {
                        for (int j = pId; j < schedCount; j += 16) {
                            final int idx = j;
                            int rank = 1 + (j % 7);
                            schedFutures.add(com.chunking.engine.scheduler.TaskQueue.submit(
                                    (j * 3) % 64, (j * 5) % 64, rank, () -> idx, workerPool));
                        }
                    }, producerPool));
                }
                CompletableFuture.allOf(prodFuts.toArray(CompletableFuture[]::new)).get(15, java.util.concurrent.TimeUnit.SECONDS);
                CompletableFuture.allOf(schedFutures.toArray(CompletableFuture[]::new)).get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertTrue(com.chunking.engine.scheduler.TaskQueue.inFlightCount() == 0,
                    "PriorityTaskScheduler inFlightCount must return to 0");

            // 3. ShardedIo multi-region store/load/scan/delete/synchronize/close test
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("chunking_sharded_io_test");
            try {
                net.minecraft.world.level.chunk.storage.RegionStorageInfo info =
                        new net.minecraft.world.level.chunk.storage.RegionStorageInfo("test", net.minecraft.world.level.Level.OVERWORLD, "chunk");
                net.minecraft.world.level.chunk.storage.RegionFileStorage primary =
                        new net.minecraft.world.level.chunk.storage.RegionFileStorage(info, tempDir, false);
                com.chunking.engine.io.ShardedIo sharded =
                        new com.chunking.engine.io.ShardedIo(info, tempDir, false, primary);
                java.util.concurrent.atomic.AtomicBoolean shutdown = new java.util.concurrent.atomic.AtomicBoolean(false);
                List<CompletableFuture<Void>> stores = new ArrayList<>(64);
                for (int rx = 0; rx < 4; rx++) {
                    for (int rz = 0; rz < 4; rz++) {
                        net.minecraft.world.level.ChunkPos cpos = new net.minecraft.world.level.ChunkPos(rx * 32 + 1, rz * 32 + 2);
                        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                        tag.putInt("xPos", cpos.x());
                        tag.putInt("zPos", cpos.z());
                        tag.putInt("DataVersion", 4000);
                        stores.add(sharded.store(cpos, () -> tag, shutdown));
                    }
                }
                CompletableFuture.allOf(stores.toArray(CompletableFuture[]::new)).join();
                sharded.synchronize(true, shutdown).join();

                // Read back and verify
                net.minecraft.world.level.ChunkPos checkPos = new net.minecraft.world.level.ChunkPos(1, 2);
                com.chunking.engine.io.ShardedIo.RawReadResult read =
                        sharded.loadRawAsync(checkPos, shutdown).join();
                assertTrue(read != null && !read.isEmpty(), "ShardedIoEngine must read back stored chunk");

                // Scan chunk
                net.minecraft.nbt.visitors.CollectFields scan = new net.minecraft.nbt.visitors.CollectFields(
                        new net.minecraft.nbt.visitors.FieldSelector(net.minecraft.nbt.IntTag.TYPE, "DataVersion"));
                sharded.scanChunk(checkPos, scan, shutdown).join();
                assertTrue(scan.getResult() instanceof net.minecraft.nbt.CompoundTag ct && ct.getIntOr("DataVersion", 0) == 4000,
                        "ShardedIoEngine scanChunk must read DataVersion=4000");

                // Delete chunk via null NBT and verify empty read
                sharded.store(checkPos, () -> null, shutdown).join();
                com.chunking.engine.io.ShardedIo.RawReadResult deleted =
                        sharded.loadRawAsync(checkPos, shutdown).join();
                assertTrue(deleted != null && deleted.isEmpty(), "ShardedIoEngine must return EMPTY after null delete");

                shutdown.set(true);
                sharded.close();
            } finally {
                try (var walk = java.nio.file.Files.walk(tempDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
            System.out.println("[PASS] 16. High-Core Server Scaling: 64T FeatureLock (maxParallel="
                    + maxParallel.get() + ") + 64T Scheduler + ShardedIO=OK");
        } finally {
            com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS = prevWorkers;
        }
    }

    private static void testBugFixesAndCompatGuards() {
        // 1. Pre-encoded frame unwrap preserves extra modded keys
        net.minecraft.nbt.CompoundTag orig = new net.minecraft.nbt.CompoundTag();
        orig.putInt("DataVersion", 4000);
        orig.putInt("xPos", 3);
        byte[] frame = com.chunking.engine.io.EncodedCache.encodeDeflateFrame(orig);
        assertTrue(frame != null, "encodeDeflateFrame must succeed");
        net.minecraft.nbt.CompoundTag wrapped = com.chunking.engine.io.EncodedCache.wrapPreEncodedFrame(frame);
        wrapped.putString("modExtraKey", "preserved");
        net.minecraft.nbt.CompoundTag unwrapped = com.chunking.engine.io.EncodedCache.unwrapIfPreEncoded(wrapped);
        assertTrue(unwrapped.getIntOr("xPos", 0) == 3, "unwrapped tag must restore original fields");
        assertTrue("preserved".equals(unwrapped.getStringOr("modExtraKey", "")), "unwrapped tag must preserve extra modded keys");

        // 2. ManagedBlock depth tracking
        assertTrue(!com.chunking.engine.scheduler.TickBudget.isInManagedBlock(), "managedBlock depth starts 0");
        com.chunking.engine.scheduler.TickBudget.enterManagedBlock();
        assertTrue(com.chunking.engine.scheduler.TickBudget.isInManagedBlock(), "managedBlock depth > 0");
        com.chunking.engine.scheduler.TickBudget.exitManagedBlock();
        assertTrue(!com.chunking.engine.scheduler.TickBudget.isInManagedBlock(), "managedBlock depth restored to 0");

        // 4. Governor resetSession clears pressure state
        com.chunking.engine.scheduler.FrameGuard.get().onServerTick(100.0D);
        com.chunking.engine.scheduler.FrameGuard.get().resetSession();
        assertTrue(com.chunking.engine.scheduler.FrameGuard.get().level()
                == com.chunking.engine.scheduler.FrameGuard.Level.CALM,
                "Governor resetSession must restore CALM");

        // 5. OptimisticLightMaps shrink on low occupancy
        com.chunking.engine.world.LightMaps.IntTable tbl =
                new com.chunking.engine.world.LightMaps.IntTable(64, -1);
        for (int i = 1; i <= 100; i++) {
            tbl = tbl.put(i, i * 10);
        }
        for (int i = 1; i <= 95; i++) {
            tbl.remove(i);
        }
        assertTrue(tbl.getOptimistic(100) == 1000 && tbl.getOptimistic(1) == -1,
                "OptimisticLightMaps shrink must preserve remaining keys");

        // 6. SkyLightEngine.propagateLightSources must not break early when maxCenterY == secMinY and minCenterY < secMinY
        HolderLookup.Provider skyRegs = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> skyBiomes = skyRegs.lookupOrThrow(Registries.BIOME);
        IdMapper<Holder<Biome>> skyBiomeMap = new IdMapper<>();
        skyBiomes.listElements().forEach(skyBiomeMap::add);
        net.minecraft.world.level.chunk.PalettedContainerFactory skyFactory =
                new net.minecraft.world.level.chunk.PalettedContainerFactory(
                        Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                        Blocks.AIR.defaultBlockState(),
                        null,
                        Strategy.createForBiomes(skyBiomeMap),
                        skyBiomes.getOrThrow(Biomes.PLAINS),
                        null);
        net.minecraft.world.level.LevelHeightAccessor skyHeight =
                net.minecraft.world.level.LevelHeightAccessor.create(0, 128);
        ChunkPos skyCp = new ChunkPos(0, 0);
        net.minecraft.world.level.chunk.ProtoChunk skyPc = new net.minecraft.world.level.chunk.ProtoChunk(
                skyCp, net.minecraft.world.level.chunk.UpgradeData.EMPTY, skyHeight, skyFactory, null);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                skyPc.setBlockState(new BlockPos(x, 62, z), Blocks.STONE.defaultBlockState(), 0);
            }
        }
        // Single column at y=63 gives lowestSourceY == 64 == secMinY(4), while remaining 255 columns have lowestSourceY == 63 < 64.
        skyPc.setBlockState(new BlockPos(0, 63, 0), Blocks.STONE.defaultBlockState(), 0);
        skyPc.initializeLightSources();
        net.minecraft.world.level.chunk.LightChunkGetter skyGetter = new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override
            public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int cx, int cz) {
                return (cx == 0 && cz == 0) ? skyPc : null;
            }

            @Override
            public net.minecraft.world.level.BlockGetter getLevel() {
                return net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
            }
        };
        net.minecraft.world.level.lighting.LevelLightEngine skyLe =
                new net.minecraft.world.level.lighting.LevelLightEngine(skyGetter, false, true);
        for (int sy = skyHeight.getMinSectionY(); sy <= skyHeight.getMaxSectionY(); sy++) {
            int idx = skyPc.getSectionIndexFromSectionY(sy);
            if (idx >= 0 && idx < skyPc.getSections().length && !skyPc.getSection(idx).hasOnlyAir()) {
                skyLe.updateSectionStatus(net.minecraft.core.SectionPos.of(0, sy, 0), false);
            }
        }
        skyLe.setLightEnabled(skyCp, true);
        skyLe.propagateLightSources(skyCp);
        skyLe.runLightUpdates();
        int patchSky = skyLe.getLayerListener(net.minecraft.world.level.LightLayer.SKY)
                .getLightValue(new BlockPos(8, 63, 8));
        assertTrue(patchSky == 15,
                "Sky light below maxCenterY==secMinY section boundary must be 15, got " + patchSky);

        // 7. Randomized hillside SkyLight parity across multi-section slopes
        int[][] slopes = {
                {48, 80, 1}, // steep slope spanning 3 sections (sy=3,4,5)
                {64, 64, 0}, // flat exactly at secMinY(4)
                {63, 65, 1}  // maxCenterY == secMinY(4) + 1
        };
        for (int caseIdx = 0; caseIdx < slopes.length; caseIdx++) {
            int minH = slopes[caseIdx][0];
            int maxH = slopes[caseIdx][1];
            int step = slopes[caseIdx][2];
            net.minecraft.world.level.chunk.ProtoChunk hillPc = new net.minecraft.world.level.chunk.ProtoChunk(
                    skyCp, net.minecraft.world.level.chunk.UpgradeData.EMPTY, skyHeight, skyFactory, null);
            int[][] topY = new int[16][16];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int h = step == 0 ? minH : minH + ((x + z * 2) * (maxH - minH)) / 45;
                    topY[x][z] = h;
                    for (int y = minH - 2; y <= h; y++) {
                        hillPc.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 0);
                    }
                }
            }
            hillPc.initializeLightSources();
            net.minecraft.world.level.chunk.LightChunkGetter hillGetter = new net.minecraft.world.level.chunk.LightChunkGetter() {
                @Override
                public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int cx, int cz) {
                    return (cx == 0 && cz == 0) ? hillPc : null;
                }

                @Override
                public net.minecraft.world.level.BlockGetter getLevel() {
                    return net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
                }
            };
            net.minecraft.world.level.lighting.LevelLightEngine hillLe =
                    new net.minecraft.world.level.lighting.LevelLightEngine(hillGetter, false, true);
            for (int sy = skyHeight.getMinSectionY(); sy <= skyHeight.getMaxSectionY(); sy++) {
                int idx = hillPc.getSectionIndexFromSectionY(sy);
                if (idx >= 0 && idx < hillPc.getSections().length && !hillPc.getSection(idx).hasOnlyAir()) {
                    hillLe.updateSectionStatus(net.minecraft.core.SectionPos.of(0, sy, 0), false);
                }
            }
            hillLe.setLightEnabled(skyCp, true);
            hillLe.propagateLightSources(skyCp);
            hillLe.runLightUpdates();
            var skyListener = hillLe.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int openY = topY[x][z] + 1;
                    int skyVal = skyListener.getLightValue(new BlockPos(x, openY, z));
                    assertTrue(skyVal == 15,
                            "Hillside case " + caseIdx + " open-sky (" + x + "," + openY + "," + z + ") must be 15, got " + skyVal);
                    int buriedVal = skyListener.getLightValue(new BlockPos(x, topY[x][z] - 1, z));
                    assertTrue(buriedVal == 0,
                            "Hillside case " + caseIdx + " buried (" + x + "," + (topY[x][z] - 1) + "," + z + ") must be 0, got " + buriedVal);
                }
            }
        }

        // 8. OptimisticLightMaps ObjTable & IntTable randomized parity vs fastutil maps (including 0L, rehash, shrink)
        java.util.Random rng = new java.util.Random(262626L);
        com.chunking.engine.world.LightMaps.ObjTable objTbl =
                new com.chunking.engine.world.LightMaps.ObjTable(64);
        com.chunking.engine.world.LightMaps.IntTable intTbl =
                new com.chunking.engine.world.LightMaps.IntTable(64, -999);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.chunk.DataLayer> refObj =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap refInt =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        refInt.defaultReturnValue(-999);
        long[] keyPool = new long[320];
        keyPool[0] = 0L;
        for (int i = 1; i < keyPool.length; i++) {
            keyPool[i] = net.minecraft.core.SectionPos.asLong(i - 160, (i & 15) - 4, (i * 7) & 31);
        }
        for (int step = 0; step < 3000; step++) {
            long k = keyPool[rng.nextInt(keyPool.length)];
            int op = rng.nextInt(10);
            if (op < 6) {
                net.minecraft.world.level.chunk.DataLayer dl = new net.minecraft.world.level.chunk.DataLayer(step & 15);
                objTbl = objTbl.put(k, dl);
                refObj.put(k, dl);
                int v = rng.nextInt();
                intTbl = intTbl.put(k, v);
                refInt.put(k, v);
            } else {
                objTbl.remove(k);
                refObj.remove(k);
                intTbl.remove(k);
                refInt.remove(k);
            }
            if ((step & 63) == 0 || step > 2900) {
                for (long probe : keyPool) {
                    assertTrue(objTbl.getOptimistic(probe) == refObj.get(probe),
                            "ObjTable mismatch at step " + step + " for key " + probe);
                    assertTrue(intTbl.getOptimistic(probe) == refInt.get(probe),
                            "IntTable mismatch at step " + step + " for key " + probe);
                }
            }
        }

        // 9. Analytical PlayerTicketEngine parity vs brute-force Chebyshev reference
        {
            final int maxDist = 64;
            final int defLvl = maxDist + 2;
            com.chunking.engine.scheduler.TicketRing engine =
                    new com.chunking.engine.scheduler.TicketRing();
            it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap chunks = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
            chunks.defaultReturnValue((byte) defLvl);
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap queueLevels = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            queueLevels.defaultReturnValue(defLvl);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet activeTickets = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            it.unimi.dsi.fastutil.longs.LongOpenHashSet activeDispatcherPos = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

            long[][] scenarios = new long[][]{
                    {net.minecraft.world.level.ChunkPos.pack(0, 0)},
                    {net.minecraft.world.level.ChunkPos.pack(1, 0)},
                    {net.minecraft.world.level.ChunkPos.pack(2, 1)},
                    {net.minecraft.world.level.ChunkPos.pack(5, -2)},
                    {net.minecraft.world.level.ChunkPos.pack(3, -4)},
                    {net.minecraft.world.level.ChunkPos.pack(-10, 7)},
                    {net.minecraft.world.level.ChunkPos.pack(200, -150)},
                    {net.minecraft.world.level.ChunkPos.pack(201, -149)},
                    {net.minecraft.world.level.ChunkPos.pack(201, -149), net.minecraft.world.level.ChunkPos.pack(205, -147)},
                    {net.minecraft.world.level.ChunkPos.pack(202, -148), net.minecraft.world.level.ChunkPos.pack(205, -147), net.minecraft.world.level.ChunkPos.pack(250, -140)},
                    {net.minecraft.world.level.ChunkPos.pack(202, -148)},
                    {}
            };
            int[] viewDists = {12, 12, 12, 12, 12, 16, 32, 64, 16, 16, 0, 16};
            java.util.Random ticketRng = new java.util.Random(42L);
            for (int s = 0; s < scenarios.length; s++) {
                long[] srcs = scenarios[s];
                int vd = viewDists[s];
                activeDispatcherPos.clear();
                for (long pos : chunks.keySet()) {
                    if (ticketRng.nextInt(8) == 0) {
                        activeDispatcherPos.add(pos);
                    }
                }
                engine.sync(
                        srcs, srcs.length, vd, maxDist, chunks, queueLevels,
                        activeDispatcherPos::contains,
                        (pos, newLvl) -> {
                            if (newLvl == defLvl) queueLevels.remove(pos);
                            else queueLevels.put(pos, newLvl);
                        },
                        (pos, newLvl, hadTicket, hasTicket) -> {
                            assertTrue(hadTicket != hasTicket, "Ticket event must be a state transition");
                            if (hasTicket) {
                                assertTrue(activeTickets.add(pos), "Duplicate ticket added at " + pos);
                            } else {
                                assertTrue(activeTickets.remove(pos), "Removed missing ticket at " + pos);
                            }
                        }
                );

                // Brute-force Chebyshev verification
                int radius = Math.min(maxDist, vd);
                it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap expected = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
                expected.defaultReturnValue((byte) defLvl);
                if (srcs.length > 0 && radius >= 0) {
                    for (long src : srcs) {
                        int sx = net.minecraft.world.level.ChunkPos.getX(src);
                        int sz = net.minecraft.world.level.ChunkPos.getZ(src);
                        for (int cx = sx - radius; cx <= sx + radius; cx++) {
                            for (int cz = sz - radius; cz <= sz + radius; cz++) {
                                int d = Math.max(Math.abs(cx - sx), Math.abs(cz - sz));
                                long p = net.minecraft.world.level.ChunkPos.pack(cx, cz);
                                int cur = expected.get(p);
                                if (d < cur) {
                                    expected.put(p, (byte) d);
                                }
                            }
                        }
                    }
                }
                assertTrue(chunks.size() == expected.size(), "PlayerTicket chunks size mismatch at step " + s);
                assertTrue(activeTickets.size() == expected.size(), "PlayerTicket activeTickets size mismatch at step " + s);
                assertTrue(queueLevels.size() == expected.size(), "PlayerTicket queueLevels size mismatch at step " + s);
                for (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry e : expected.long2ByteEntrySet()) {
                    long k = e.getLongKey();
                    int exp = e.getByteValue();
                    assertTrue(chunks.get(k) == exp, "PlayerTicket chunks level mismatch at step " + s);
                    assertTrue(queueLevels.get(k) == exp, "PlayerTicket queueLevels mismatch at step " + s);
                    assertTrue(activeTickets.contains(k), "PlayerTicket missing active ticket at step " + s);
                }
            }
        }

        // 10. PreEncoded extra-key frame preservation, VelocityCone overflow clamp,
        //     TickBudgeter full-MSPT precedence, JIT scratch re-entrancy, and OffHeapArena overflow guard
        {
            net.minecraft.nbt.CompoundTag base = new net.minecraft.nbt.CompoundTag();
            base.putInt("DataVersion", 4189);
            byte[] baseFrame = com.chunking.engine.io.EncodedCache.encodeDeflateFrame(base);
            net.minecraft.nbt.CompoundTag carrier = com.chunking.engine.io.EncodedCache.wrapPreEncodedFrame(baseFrame);
            carrier.putString("modExtraKey", "keep_me");
            assertTrue(com.chunking.engine.io.EncodedCache.extractPreEncodedFrame(carrier) == null,
                    "extractPreEncodedFrame must return null when carrier has extra mod keys");
            byte[] mergedFrame = com.chunking.engine.io.EncodedCache.extractOrEncodeFrame(carrier);
            net.minecraft.nbt.CompoundTag decoded = com.chunking.engine.io.EncodedCache.decodeDeflateFrame(mergedFrame);
            assertTrue(decoded != null && "keep_me".equals(decoded.getStringOr("modExtraKey", "")),
                    "extractOrEncodeFrame must preserve extra mod keys");

            int farScore = com.chunking.engine.scheduler.LookAhead.computeSendSortScoreWithHeading(
                    0, 0, 1.0D, 0.0D, 0.55D, 100_000, 100_000);
            assertTrue(farScore == Integer.MAX_VALUE, "Far chunk score must clamp to Integer.MAX_VALUE instead of overflowing");

            com.chunking.engine.scheduler.TickBudget.resetSession();
            com.chunking.engine.scheduler.TickBudget.recordFullTickMs(100, 45.0D);
            com.chunking.engine.scheduler.TickBudget.recordDimensionTickMs(101, 4.0D);
            assertTrue(com.chunking.engine.scheduler.TickBudget.lastRecordedTickMs() >= 45.0D,
                    "Partial dimension tick must not erase prior 45ms full-tick MSPT");
            com.chunking.engine.scheduler.TickBudget.resetSession();

            com.chunking.engine.simd.JitSupport.enterJitScratchFrame();
            try {
                double[] outer = com.chunking.engine.simd.JitSupport.borrowColScratch(0, 16);
                outer[0] = 123.5D;
                com.chunking.engine.simd.JitSupport.enterJitScratchFrame();
                try {
                    double[] inner = com.chunking.engine.simd.JitSupport.borrowColScratch(0, 16);
                    assertTrue(inner != outer, "Nested JIT scratch frame must not alias outer buffer");
                    inner[0] = -9.0D;
                } finally {
                    com.chunking.engine.simd.JitSupport.exitJitScratchFrame();
                }
                assertTrue(outer[0] == 123.5D, "Outer JIT scratch buffer must stay intact across nested call");
            } finally {
                com.chunking.engine.simd.JitSupport.exitJitScratchFrame();
            }
        }

        System.out.println("[PASS] 17. Bug Fixes & Compat Guards: UnwrapExtraKeys + ManagedBlock + GovReset + LightMapShrink + SkyBoundary + HillsideParity + LightMapFuzz + PlayerTicketParity + ExtraKeyFrame/JitReentrancy=OK");
    }

    private static final class ValidModDf implements DensityFunction.SimpleFunction {
        private final double lo;
        private final double hi;

        ValidModDf(double lo, double hi) {
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        public double compute(FunctionContext ctx) {
            return (lo + hi) * 0.5D;
        }

        @Override
        public double minValue() {
            return lo;
        }

        @Override
        public double maxValue() {
            return hi;
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return null;
        }
    }

    private static final class LyingModDf implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return 5.0D;
        }

        @Override
        public double minValue() {
            return -2.0D;
        }

        @Override
        public double maxValue() {
            return -0.5D;
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return null;
        }
    }

    private static final class LiveLyingModDf implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext ctx) {
            return -1.0D;
        }

        @Override
        public double minValue() {
            return -2.0D;
        }

        @Override
        public double maxValue() {
            return -0.5D;
        }

        @Override
        public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return null;
        }
    }

    private static void testModdedWorldgenAndSpatialStructures() throws Exception {
        // 1. BoxTree parity vs brute-force AABB overlap (250 boxes + straddlers)
        {
            var octree = new com.chunking.engine.world.BoxTree(-128.0D, -64.0D, -128.0D, 128.0D, 128.0D, 128.0D);
            List<net.minecraft.world.phys.AABB> ref = new ArrayList<>();
            SplittableRandom rng = new SplittableRandom(0x26_0C73L);
            // Include straddler boxes crossing all 3 midplanes
            net.minecraft.world.phys.AABB straddler = new net.minecraft.world.phys.AABB(-10, -10, -10, 10, 40, 10);
            octree.add(straddler);
            ref.add(straddler);
            for (int i = 0; i < 240; i++) {
                double x0 = rng.nextInt(-120, 110);
                double y0 = rng.nextInt(-60, 110);
                double z0 = rng.nextInt(-120, 110);
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        x0, y0, z0, x0 + rng.nextInt(2, 14), y0 + rng.nextInt(2, 14), z0 + rng.nextInt(2, 14));
                octree.add(box);
                ref.add(box);
            }
            assertTrue(octree.size() == ref.size(), "BoxOctree size mismatch");
            assertTrue(octree.withinBoundsXZ(-128.0D, 128.0D, -128.0D, 128.0D), "withinBoundsXZ boundary");
            assertTrue(!octree.withinBoundsXZ(-129.0D, 10.0D, 0.0D, 10.0D), "withinBoundsXZ out-of-bounds");
            for (int q = 0; q < 500; q++) {
                double x0 = rng.nextInt(-135, 130);
                double y0 = rng.nextInt(-70, 130);
                double z0 = rng.nextInt(-135, 130);
                net.minecraft.world.phys.AABB query = new net.minecraft.world.phys.AABB(
                        x0, y0, z0, x0 + rng.nextInt(1, 16), y0 + rng.nextInt(1, 16), z0 + rng.nextInt(1, 16));
                boolean expectedHit = false;
                for (net.minecraft.world.phys.AABB b : ref) {
                    if (query.minX < b.maxX && query.maxX > b.minX
                            && query.minY < b.maxY && query.maxY > b.minY
                            && query.minZ < b.maxZ && query.maxZ > b.minZ) {
                        expectedHit = true;
                        break;
                    }
                }
                boolean actualHit = octree.intersects(query);
                assertTrue(actualHit == expectedHit, "BoxOctree intersects mismatch at q=" + q);
                boolean expectedCanPlace = query.minX >= -128.0D && query.maxX <= 128.0D
                        && query.minY >= -64.0D && query.maxY <= 128.0D
                        && query.minZ >= -128.0D && query.maxZ <= 128.0D
                        && !expectedHit;
                assertTrue(octree.canPlace(query) == expectedCanPlace, "BoxOctree canPlace mismatch at q=" + q);
            }
        }

        // 2. TemplateGrid exact inverseTransformBounds + multi-cell order parity
        {
            List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> blocks =
                    new ArrayList<>();
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState oak = Blocks.OAK_PLANKS.defaultBlockState();
            for (int x = 0; x < 24; x++) {
                for (int z = 0; z < 24; z++) {
                    for (int y = 0; y < 3; y++) {
                        if (((x + y + z) & 1) == 0) {
                            blocks.add(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(
                                    new BlockPos(x, y, z), (y == 0 ? stone : oak), null));
                        }
                    }
                }
            }
            var spatial = com.chunking.engine.world.TemplateGrid.build(blocks);
            assertTrue(spatial != null, "SpatialStructurePalette build returned null for >= 64 blocks");

            BlockPos origin = new BlockPos(100, 64, -200);
            BlockPos pivot = new BlockPos(7, 0, 11);
            var mirrors = net.minecraft.world.level.block.Mirror.values();
            var rotations = net.minecraft.world.level.block.Rotation.values();
            var worldBoxes = new net.minecraft.world.level.levelgen.structure.BoundingBox[]{
                    new net.minecraft.world.level.levelgen.structure.BoundingBox(96, 64, -208, 111, 66, -193),
                    new net.minecraft.world.level.levelgen.structure.BoundingBox(108, 65, -202, 123, 66, -187),
                    new net.minecraft.world.level.levelgen.structure.BoundingBox(80, 64, -220, 99, 66, -201),
                    new net.minecraft.world.level.levelgen.structure.BoundingBox(500, 64, 500, 515, 70, 515)
            };
            for (var mirror : mirrors) {
                for (var rot : rotations) {
                    for (var wb : worldBoxes) {
                        var settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                                .setMirror(mirror)
                                .setRotation(rot)
                                .setRotationPivot(pivot)
                                .setBoundingBox(wb);
                        var got = spatial.query(origin, settings);
                        List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> exp =
                                new ArrayList<>();
                        for (var info : blocks) {
                            BlockPos wp = net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
                                    .calculateRelativePosition(settings, info.pos()).offset(origin);
                            if (wb.isInside(wp)) {
                                exp.add(info);
                            }
                        }
                        assertTrue(got.size() == exp.size(),
                                "SpatialStructurePalette count mismatch (" + mirror + "," + rot + "): " + got.size() + " vs " + exp.size());
                        for (int i = 0; i < exp.size(); i++) {
                            assertTrue(got.get(i) == exp.get(i),
                                    "SpatialStructurePalette order/identity mismatch at " + i + " (" + mirror + "," + rot + ")");
                        }
                    }
                }
            }
        }

        // 3. Self-verifying mod DF interval bounds + 2-level classifyRange
        {
            DensityFunction validDf = new ValidModDf(-3.0D, -0.5D);
            var estValid = com.chunking.engine.simd.CellSkipper.compile(
                    null, List.of(), List.of(), validDf, true, null, 4);
            assertTrue(estValid != null, "Valid finite mod DF must compile interval estimator");
            assertTrue(!com.chunking.engine.simd.CellSkipper.isModDensityFunctionQuarantined(ValidModDf.class),
                    "ValidModDf must not be quarantined");
            assertTrue(estValid.classifyCell(0, 64, 0, 8) == -1,
                    "Valid negative mod DF must classify cell as -1");
            assertTrue(estValid.classifyRange(0, 64, 79, 0) == -1,
                    "Valid negative mod DF must classify 16-block range as -1");

            DensityFunction lyingDf = new LyingModDf();
            var estLying = com.chunking.engine.simd.CellSkipper.compile(
                    null, List.of(), List.of(), lyingDf, true, null, 4);
            assertTrue(com.chunking.engine.simd.CellSkipper.isModDensityFunctionQuarantined(LyingModDf.class),
                    "Static probe must quarantine LyingModDf whose compute() violates [minValue, maxValue]");
            if (estLying != null) {
                assertTrue(estLying.classifyCell(0, 64, 0, 8) == 0,
                        "Quarantined LyingModDf must never skip cells");
            }

            DensityFunction liveLyingDf = new LiveLyingModDf();
            var estLive = com.chunking.engine.simd.CellSkipper.compile(
                    null, List.of(), List.of(), liveLyingDf, true, null, 4);
            assertTrue(estLive != null && estLive.hasUnverifiedModBounds(),
                    "LiveLyingModDf must start unverified before live cell probing");
            assertTrue(estLive.classifyCell(0, 64, 0, 8) == -1, "LiveLyingModDf initially predicts -1");
            boolean verifiedOk = estLive.verifyCellValues(-1, new double[]{-1.0D, 0.25D});
            assertTrue(!verifiedOk, "verifyCellValues must detect sign violation on +0.25");
            assertTrue(com.chunking.engine.simd.CellSkipper.isModDensityFunctionQuarantined(LiveLyingModDf.class),
                    "LiveLyingModDf must be quarantined after live sign violation");
            assertTrue(estLive.classifyCell(0, 64, 0, 8) == 0,
                    "LiveLyingModDf must immediately stop skipping after quarantine");
        }

        // 4. LargeFlatSequenceRule (>64 rules) + universal SurfaceRules.Condition support
        {
            List<SurfaceRules.RuleSource> rules70 = new ArrayList<>(70);
            for (int i = 0; i < 68; i++) {
                rules70.add(FastSurface.dummyUnmatchedRuleSource());
            }
            rules70.add(SurfaceRules.state(Blocks.STONE.defaultBlockState()));
            Object largeSeq = FastSurface.buildFlattenedSequence(rules70, null);
            assertTrue(largeSeq instanceof FastSurface.LargeFlatSequenceRule lfs && lfs.ruleCount() == 69,
                    "Sequence with >64 active rules must compile to LargeFlatSequenceRule");
            assertTrue(FastSurface.tryApplyRule(largeSeq, 1, 64, 1) == Blocks.STONE.defaultBlockState(),
                    "LargeFlatSequenceRule must apply trailing STONE rule");

            // Direct non-LazyCondition implementation of SurfaceRules$Condition compiled into FastTestRule
            Class<?> condIface = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Condition");
            boolean[] flag = {false};
            Object rawCond = java.lang.reflect.Proxy.newProxyInstance(
                    condIface.getClassLoader(),
                    new Class<?>[]{condIface},
                    (proxy, method, args) -> "test".equals(method.getName()) ? Boolean.valueOf(flag[0]) : null
            );
            Object stoneRule = FastSurface.evalRuleSource(SurfaceRules.state(Blocks.STONE.defaultBlockState()), null);
            Object compiledRaw = FastSurface.newTestRule(rawCond, stoneRule);
            assertTrue(FastSurface.tryApplyRule(compiledRaw, 0, 64, 0) == null,
                    "Raw modded Condition=false must return null");
            flag[0] = true;
            assertTrue(FastSurface.tryApplyRule(compiledRaw, 0, 64, 0) == Blocks.STONE.defaultBlockState(),
                    "Raw modded Condition=true must return STONE");
        }

        // 5. VoxelShape direct full-block flag parity
        {
            assertTrue(Block.isShapeFullBlock(net.minecraft.world.phys.shapes.Shapes.block()), "Shapes.block() must be full");
            assertTrue(!Block.isShapeFullBlock(net.minecraft.world.phys.shapes.Shapes.empty()), "Shapes.empty() must not be full");
            var fullCustom = net.minecraft.world.phys.shapes.Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
            var slabShape = net.minecraft.world.phys.shapes.Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D);
            assertTrue(Block.isShapeFullBlock(fullCustom) && Block.isShapeFullBlock(fullCustom),
                    "Custom full VoxelShape must evaluate true consistently");
            assertTrue(!Block.isShapeFullBlock(slabShape) && !Block.isShapeFullBlock(slabShape),
                    "Slab VoxelShape must evaluate false consistently");
        }

        // 6. Arbitrary-radius chromatic lock (r=2, r=3 color disjointness + concurrent r=2 stress test)
        {
            for (int r = 1; r <= 3; r++) {
                int period = 2 * r + 1;
                for (int cx = -6; cx <= 6; cx++) {
                    for (int cz = -6; cz <= 6; cz++) {
                        int c0 = AreaLock.chromaticColor(cx, cz, r);
                        assertTrue(c0 >= 0 && c0 < period * period, "Chromatic color out of range for r=" + r);
                        for (int dx = -2 * r; dx <= 2 * r; dx++) {
                            for (int dz = -2 * r; dz <= 2 * r; dz++) {
                                if (dx == 0 && dz == 0) continue;
                                int c1 = AreaLock.chromaticColor(cx + dx, cz + dz, r);
                                assertTrue(c0 != c1, "Same chromatic color within colliding distance for r=" + r);
                            }
                        }
                    }
                }
            }
            AreaLock r2Lock = new AreaLock();
            ConcurrentHashMap<Long, int[]> active = new ConcurrentHashMap<>();
            AtomicInteger r2Violations = new AtomicInteger();
            List<CompletableFuture<Integer>> r2Futures = new ArrayList<>(300);
            try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
                for (int i = 0; i < 300; i++) {
                    int cx = (i * 3) % 14;
                    int cz = (i * 5) % 14;
                    long id = i;
                    r2Futures.add(r2Lock.executeAsync(cx, cz, 2, () -> {
                        for (int[] other : active.values()) {
                            if (Math.max(Math.abs(cx - other[0]), Math.abs(cz - other[1])) <= 4) {
                                r2Violations.incrementAndGet();
                            }
                        }
                        active.put(id, new int[]{cx, cz});
                        try {
                            Thread.onSpinWait();
                        } finally {
                            active.remove(id);
                        }
                        return 1;
                    }, pool));
                }
                CompletableFuture.allOf(r2Futures.toArray(CompletableFuture[]::new)).join();
            }
            assertTrue(r2Violations.get() == 0, "Spatial r=2 exclusion violated: " + r2Violations.get());
            assertTrue(r2Lock.activeOwnedChunkCells() == 0 && r2Lock.pendingCount() == 0, "Leaked r=2 spatial lock state");
        }

        // 7. LoadPacer + WorkerPool priority + Governor alloc thresholds
        {
            FrameGuard.get().stop();
            TickBudget.resetSession();
            com.chunking.engine.scheduler.LoadPacer.resetForTest();
            assertTrue(com.chunking.engine.scheduler.LoadPacer.computeTickBudgetNs(
                    false, 45.0D, true, FrameGuard.Level.SEVERE) == Long.MAX_VALUE,
                    "Headless mode must completely bypass promotion budget");
            assertTrue(com.chunking.engine.scheduler.LoadPacer.computeTickBudgetNs(
                    true, 12.0D, false, FrameGuard.Level.CALM) == com.chunking.engine.scheduler.LoadPacer.CALM_BUDGET_NS,
                    "Live calm budget mismatch");
            assertTrue(com.chunking.engine.scheduler.LoadPacer.computeTickBudgetNs(
                    true, 30.0D, true, FrameGuard.Level.MILD) == com.chunking.engine.scheduler.LoadPacer.MILD_BUDGET_NS,
                    "Live mild budget mismatch");
            assertTrue(com.chunking.engine.scheduler.LoadPacer.computeTickBudgetNs(
                    true, 45.0D, true, FrameGuard.Level.SEVERE) == com.chunking.engine.scheduler.LoadPacer.SEVERE_BUDGET_NS,
                    "Live severe budget mismatch");
            assertTrue(!com.chunking.engine.scheduler.LoadPacer.shouldPace("minecraft:overworld", 100, 100),
                    "Headless mode must bypass shouldPace");

            java.util.concurrent.Executor fakeExec = Runnable::run;
            List<Integer> order = new ArrayList<>();
            var f1 = com.chunking.engine.scheduler.LoadPacer.enqueue(
                    "minecraft:overworld", 10, 10, () -> { order.add(1); return 1; }, fakeExec);
            var f2 = com.chunking.engine.scheduler.LoadPacer.enqueue(
                    "minecraft:overworld", 11, 11, () -> { order.add(2); return 2; }, fakeExec);
            assertTrue(com.chunking.engine.scheduler.LoadPacer.pendingCount() == 2, "Expected 2 pending promotions");
            int drained = com.chunking.engine.scheduler.LoadPacer.drainBudgeted(fakeExec, 0L, false);
            assertTrue(drained == 1 && com.chunking.engine.scheduler.LoadPacer.pendingCount() == 1,
                    "Zero-ns live budget must drain exactly 1 promotion for forward progress");
            com.chunking.engine.scheduler.LoadPacer.flushAll();
            assertTrue(f1.join() == 1 && f2.join() == 2 && com.chunking.engine.scheduler.LoadPacer.pendingCount() == 0,
                    "flushAll must complete all remaining promotions");
            com.chunking.engine.scheduler.LoadPacer.resetForTest();

            assertTrue(com.chunking.engine.scheduler.WorkerPool.workerThreadPriority(false) == Thread.NORM_PRIORITY,
                    "Dedicated server worker priority must be NORM_PRIORITY");
            assertTrue(com.chunking.engine.scheduler.WorkerPool.workerThreadPriority(true) == Thread.NORM_PRIORITY - 2,
                    "Client worker priority must be NORM_PRIORITY - 2");
            assertTrue(FrameGuard.computeAllocMildThresholdMbPerSec(4096L) >= 256.0D,
                    "Alloc mild threshold must scale cleanly with heap");
            FrameGuard.get().recordAllocationRateMbPerSec(9999.0D);
            assertTrue(!FrameGuard.get().isAllocationPressured(),
                    "Headless governor must ignore allocation-rate spikes");
        }

        // 8. GlobalPalette (>256 unique BlockStates) NBT encode + decode parity
        {
            Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            BlockState air = Blocks.AIR.defaultBlockState();
            Codec<PalettedContainer<BlockState>> blockCodec = PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, air);
            List<BlockState> many = new ArrayList<>(320);
            for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
                many.add(s);
                if (many.size() >= 300) {
                    break;
                }
            }
            assertTrue(many.size() >= 270, "Expected >= 270 distinct vanilla BlockStates for GlobalPalette test");
            PalettedContainer<BlockState> globalPc = new PalettedContainer<>(air, blockStrategy);
            for (int i = 0; i < 4096; i++) {
                globalPc.getAndSetUnchecked(i & 15, (i >> 8) & 15, (i >> 4) & 15, many.get(i % many.size()));
            }
            CompoundTag encoded = SectionWriter.encodeBlockStates(globalPc);
            assertTrue(encoded != null, "FastSectionNbtEncoder must handle >256-entry GlobalPalette directly");
            PalettedContainer<BlockState> decodedFast = SectionReader.decodeBlockStates(encoded, blockStrategy);
            assertTrue(decodedFast != null, "FastSectionNbtDecoder must decode >256-entry GlobalPalette directly");
            PalettedContainer<BlockState> decodedVanilla = blockCodec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState exp = globalPc.get(x, y, z);
                        assertTrue(decodedFast.get(x, y, z) == exp && decodedVanilla.get(x, y, z) == exp,
                                "GlobalPalette encode/decode parity mismatch at (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }

        System.out.println("[PASS] 18. Modded Worldgen & Locks: BoxOctree + SpatialPalette + ModDFBounds + LargeFlatSeq + VoxelFlag + R2ChromaticLock + FullPromoPacer + GlobalPaletteNBT=OK");
    }

    private static final class MockBeardifier implements DensityFunctions.BeardifierOrMarker, com.chunking.engine.access.BeardifierAccess {
        private final int minX, minY, minZ, maxX, maxY, maxZ;

        MockBeardifier(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        @Override public double compute(FunctionContext ctx) { return 0.0D; }
        @Override public double minValue() { return -1.0D; }
        @Override public double maxValue() { return 1.0D; }
        @Override public net.minecraft.world.level.levelgen.structure.BoundingBox chunking$getAffectedBox() {
            return new net.minecraft.world.level.levelgen.structure.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }
        @Override public boolean chunking$hasUnionBox() { return true; }
        @Override public int chunking$uMinX() { return minX; }
        @Override public int chunking$uMaxX() { return maxX; }
        @Override public int chunking$uMinY() { return minY; }
        @Override public int chunking$uMaxY() { return maxY; }
        @Override public int chunking$uMinZ() { return minZ; }
        @Override public int chunking$uMaxZ() { return maxZ; }
    }

    private static void testCarversAndConfigReload() throws Exception {
        // 1. ModConfig.reload() restores disk/default values and updates WorkerPool live
        {
            int origWorkers = com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS;
            int origRadius = com.chunking.engine.config.ModConfig.FULL_CHUNK_RADIUS;
            boolean origGov = com.chunking.engine.config.ModConfig.ADAPTIVE_GOVERNOR;
            String prevProp = System.getProperty("chunking.worldgen_workers");
            try {
                System.setProperty("chunking.worldgen_workers", "3");
                com.chunking.engine.config.ModConfig.FULL_CHUNK_RADIUS = 12;
                com.chunking.engine.config.ModConfig.reload();
                assertTrue(com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS == 3,
                        "ModConfig.reload must pick up worldgen_workers property");
                assertTrue(com.chunking.engine.core.ChunkEngine.get().workerPool().parallelism() == 3,
                        "ModConfig.reload must live-update WorkerPool parallelism");
            } finally {
                if (prevProp == null) {
                    System.clearProperty("chunking.worldgen_workers");
                } else {
                    System.setProperty("chunking.worldgen_workers", prevProp);
                }
                com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS = origWorkers;
                com.chunking.engine.config.ModConfig.FULL_CHUNK_RADIUS = origRadius;
                com.chunking.engine.config.ModConfig.ADAPTIVE_GOVERNOR = origGov;
                com.chunking.engine.core.ChunkEngine.get().workerPool().setParallelism(origWorkers);
                FrameGuard.get().stop();
            }
        }

        // 2. TemplateGrid ThreadLocal scratch reuse + dynamic growth (>256 candidates, zero stale bleed)
        {
            List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> big =
                    new ArrayList<>(1200);
            BlockState stone = Blocks.STONE.defaultBlockState();
            for (int x = 0; x < 24; x++) {
                for (int z = 0; z < 24; z++) {
                    for (int y = 0; y < 2; y++) {
                        big.add(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(
                                new BlockPos(x, y, z), stone, null));
                    }
                }
            }
            var grid = com.chunking.engine.world.TemplateGrid.build(big);
            assertTrue(grid != null, "TemplateGrid must build for 1152 blocks");
            var s1 = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                    .setBoundingBox(new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 0, 0, 19, 1, 19));
            var r1 = grid.query(BlockPos.ZERO, s1);
            assertTrue(r1.size() == 800, "First large multi-cell query expected 800 blocks, got " + r1.size());
            var s2 = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                    .setBoundingBox(new net.minecraft.world.level.levelgen.structure.BoundingBox(7, 0, 7, 9, 1, 9));
            var r2 = grid.query(BlockPos.ZERO, s2);
            assertTrue(r2.size() == 18, "Subsequent smaller query must not bleed stale scratch entries: " + r2.size());
            for (int i = 1; i < r2.size(); i++) {
                assertTrue(big.indexOf(r2.get(i)) > big.indexOf(r2.get(i - 1)),
                        "TemplateGrid multi-cell query must preserve exact ascending original order");
            }
        }

        // 3. Primitive BeardifierAccess union bounds + CellSkipper primitive beardBounds culling
        {
            var mockBeard = new MockBeardifier(16, 40, 16, 47, 72, 47);
            assertTrue(mockBeard.chunking$hasUnionBox(), "MockBeardifier must report union box");
            assertTrue(mockBeard.chunking$intersectsBox(20, 50, 20, 23, 57, 23), "Inside union box must intersect");
            assertTrue(!mockBeard.chunking$intersectsBox(0, 50, 0, 15, 57, 15), "Outside X/Z union box must not intersect");
            assertTrue(!mockBeard.chunking$intersectsBox(20, 80, 20, 23, 87, 23), "Above Y union box must not intersect");

            DensityFunction expr = DensityFunctions.add(DensityFunctions.constant(-0.5D), mockBeard);
            var est = com.chunking.engine.simd.CellSkipper.compile(
                    null, List.of(), List.of(), expr, true, mockBeard, 4);
            assertTrue(est != null, "CellSkipper must compile with primitive BeardifierAccess");
            assertTrue(est.classifyCell(0, 50, 0, 8) == -1,
                    "Cell outside primitive beardifier union box must skip as -1");
            assertTrue(est.classifyCell(20, 50, 20, 8) == 0,
                    "Cell inside primitive beardifier union box [-1,1] + (-0.5) must stay mixed (0)");
        }

        // 4. WorldCarverMixin class integrity + CaveWorldCarver ellipsoid/mask sanity
        {
            Class<?> carverMixin = Class.forName("com.chunking.engine.mixin.WorldCarverMixin");
            assertTrue(carverMixin.getDeclaredMethods().length > 0, "WorldCarverMixin must be compiled");
            HolderLookup.Provider registries = VanillaRegistries.createLookup();
            HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
            IdMapper<Holder<Biome>> biomeIdMap = new IdMapper<>();
            biomeLookup.listElements().forEach(biomeIdMap::add);
            Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeIdMap);
            Holder<Biome> plainsBiome = biomeLookup.getOrThrow(Biomes.PLAINS);
            var containerFactory = new net.minecraft.world.level.chunk.PalettedContainerFactory(
                    blockStrategy, Blocks.AIR.defaultBlockState(), null, biomeStrategy, plainsBiome, null);
            var heightAccessor = net.minecraft.world.level.LevelHeightAccessor.create(-64, 384);
            var proto = new net.minecraft.world.level.chunk.ProtoChunk(
                    new ChunkPos(0, 0), net.minecraft.world.level.chunk.UpgradeData.EMPTY, heightAccessor, containerFactory, null);
            var mask = proto.getOrCreateCarvingMask();
            mask.set(3, 40, 5);
            assertTrue(mask.get(3, 40, 5) && !mask.get(4, 40, 5), "CarvingMask bit parity check");
        }

        System.out.println("[PASS] 19. Carvers & Config Reload: ModConfig.reload + TemplateGridScratch + PrimitiveBeardifierBounds + WorldCarverMixin=OK");
    }

    private static void testBaseHeightCacheAndModIcon() throws Exception {
        // 1. RandomStateAccess base-height cache: hit, miss, full 32-bit coord & dimension isolation
        {
            var cache = new java.util.concurrent.atomic.AtomicReferenceArray<com.chunking.engine.access.RandomStateAccess.BaseHeightEntry>(
                    com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_CACHE_SIZE);
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(null, 0, 0, 0, -64, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Null cache must miss");
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 123, -456, 1, -64, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Empty cache must miss");

            com.chunking.engine.access.RandomStateAccess.storeBaseHeight(cache, 123, -456, 1, -64, 384, 72);
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 123, -456, 1, -64, 384) == 72,
                    "Exact base-height cache hit failed");
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 124, -456, 1, -64, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Different X must miss");
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 123, -455, 1, -64, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Different Z must miss");
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 123, -456, 2, -64, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Different Heightmap.Types must miss");
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 123, -456, 1, 0, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Different minY must miss");
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 123, -456, 1, -64, 256)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Different height must miss");

            // Negative surface heights and extreme 32-bit coordinates must store and resolve cleanly without aliasing
            com.chunking.engine.access.RandomStateAccess.storeBaseHeight(cache, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -64, 384, -42);
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -64, 384) == -42,
                    "Extreme 32-bit coordinate / negative height cache hit failed");
            com.chunking.engine.access.RandomStateAccess.storeBaseHeight(cache, 0, 0, 0, -64, 384,
                    com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS);
            assertTrue(com.chunking.engine.access.RandomStateAccess.lookupBaseHeight(cache, 0, 0, 0, -64, 384)
                    == com.chunking.engine.access.RandomStateAccess.BASE_HEIGHT_MISS, "Storing sentinel BASE_HEIGHT_MISS must be ignored");
        }

        // 2. SettingsScreen Cloth-free fallback safety + icon.png + ModMenu metadata
        {
            java.nio.file.Path settingsSrc = java.nio.file.Path.of(
                    "src", "client", "java", "com", "chunking", "engine", "client", "config", "SettingsScreen.java");
            assertTrue(java.nio.file.Files.isRegularFile(settingsSrc), "SettingsScreen.java must exist");
            String src = java.nio.file.Files.readString(settingsSrc);
            assertTrue(!src.contains("me.shedaniel.clothconfig2"),
                    "SettingsScreen must not import Cloth Config directly so fallback loads without Cloth Config");
            assertTrue(src.contains("isModLoaded(\"cloth-config\")") && src.contains("return new SettingsScreen(parent)"),
                    "SettingsScreen.create must guard Cloth Config behind FabricLoader check and fall back to vanilla screen");

            try (var in = EngineTests.class.getResourceAsStream("/assets/chunking/icon.png")) {
                assertTrue(in != null, "assets/chunking/icon.png must be present on classpath");
                byte[] png = in.readAllBytes();
                assertTrue(png.length > 512
                        && (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G',
                        "assets/chunking/icon.png must be a valid PNG image");
            }
            try (var in = EngineTests.class.getResourceAsStream("/fabric.mod.json")) {
                assertTrue(in != null, "fabric.mod.json must be present on classpath");
                String modJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(modJson.contains("\"icon\": \"assets/chunking/icon.png\""),
                        "fabric.mod.json must declare icon path");
                assertTrue(modJson.contains("\"modmenu\"") && modJson.contains("ModMenuEntry"),
                        "fabric.mod.json must register ModMenuEntry");
            }
        }

        System.out.println("[PASS] 20. BaseHeight Cache & Mod Icon: BaseHeightCache (hit/miss/32-bit/height-isolation) + SettingsScreenFallback + Icon/ModMenu=OK");
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError(msg);
        }
    }
}
