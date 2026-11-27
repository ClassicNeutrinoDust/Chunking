package com.chunking.engine.benchmark;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * Pure-Chunky headless concentric-spiral benchmark across worker counts.
 * Runs a discarded warmup spiral around (0, 0), then measures disjoint far-distance
 * Overworld terrain generation (Biomes + Noise + Surface) per worker step.
 */
public final class ChunkyBench {
    public static final long DEFAULT_SEED = 114862964781274189L;
    public static final int FAR_START_CHUNK = 62_500;
    public static final int STEP_CHUNK_STRIDE = 2_048;

    private record SweepResult(
            int workers,
            int chunks,
            double wallSec,
            double cps,
            long peakHeapMb,
            long settledHeapMb
    ) {}

    public static final class SpiralCursor {
        private final int centerX;
        private final int centerZ;
        private final int totalCount;
        private long emitted;
        private int ring;
        private int side;
        private int step;

        public SpiralCursor(int centerX, int centerZ, int radius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            int width = 2 * radius + 1;
            long countLong = (long) width * (long) width;
            if (countLong > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Spiral count exceeds Integer.MAX_VALUE: " + countLong);
            }
            this.totalCount = (int) countLong;
        }

        public int totalCount() {
            return this.totalCount;
        }

        public boolean hasNext() {
            return this.emitted < this.totalCount;
        }

        public ChunkPos nextChunkPos() {
            if (this.emitted >= this.totalCount) {
                throw new NoSuchElementException();
            }
            this.emitted++;
            if (this.ring == 0) {
                this.ring = 1;
                this.side = 0;
                this.step = 0;
                return new ChunkPos(this.centerX, this.centerZ);
            }
            int r = this.ring;
            int len = r << 1;
            int x;
            int z;
            switch (this.side) {
                case 0 -> {
                    x = this.centerX - r + this.step;
                    z = this.centerZ - r;
                }
                case 1 -> {
                    x = this.centerX + r;
                    z = this.centerZ - r + this.step;
                }
                case 2 -> {
                    x = this.centerX + r - this.step;
                    z = this.centerZ + r;
                }
                default -> {
                    x = this.centerX - r;
                    z = this.centerZ + r - this.step;
                }
            }
            this.step++;
            if (this.step >= len) {
                this.step = 0;
                this.side++;
                if (this.side >= 4) {
                    this.side = 0;
                    this.ring++;
                }
            }
            return new ChunkPos(x, z);
        }
    }

    private ChunkyBench() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int[] workers = parseWorkerSweep(System.getProperty("chunking.bench_workers"));
        int radius = Math.max(1, Integer.getInteger("chunking.bench_radius", 12));
        runHeadlessWorkerScalingSweep(workers, radius);
        ChunkEngine.get().workerPool().close();
    }

    public static int[] parseWorkerSweep(String spec) {
        if (spec == null || spec.isBlank() || "true".equalsIgnoreCase(spec.trim())) {
            return new int[]{1, 2, 4, 6, 8, 12, 16};
        }
        String[] parts = spec.split(",");
        int[] out = new int[parts.length];
        int count = 0;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 1 && v <= 64) {
                    out[count++] = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return count > 0 ? Arrays.copyOf(out, count) : new int[]{1, 2, 4, 6, 8, 12, 16};
    }

    public static List<ChunkPos> buildSquareSpiral(int centerX, int centerZ, int radius) {
        int side = 2 * radius + 1;
        List<ChunkPos> list = new ArrayList<>(side * side);
        list.add(new ChunkPos(centerX, centerZ));
        for (int r = 1; r <= radius; r++) {
            for (int x = centerX - r; x < centerX + r; x++) {
                list.add(new ChunkPos(x, centerZ - r));
            }
            for (int z = centerZ - r; z < centerZ + r; z++) {
                list.add(new ChunkPos(centerX + r, z));
            }
            for (int x = centerX + r; x > centerX - r; x--) {
                list.add(new ChunkPos(x, centerZ + r));
            }
            for (int z = centerZ + r; z > centerZ - r; z--) {
                list.add(new ChunkPos(centerX - r, z));
            }
        }
        return list;
    }

    public static void runHeadlessWorkerScalingSweep(int[] workerCounts, int radius) {
        long seed = Long.getLong("chunking.bench_seed", DEFAULT_SEED);
        int warmupRadius = Integer.getInteger("chunking.bench_warmup_radius", Math.max(2, Math.min(16, radius / 3)));
        int prevWorkers = ChunkEngine.get().workerPool().parallelism();
        boolean prevSimd = ModConfig.SIMD_NOISE;
        boolean prevParallel = ModConfig.PARALLEL_STATUS_TASKS;
        boolean prevFastRules = ModConfig.FAST_SURFACE_RULES;

        try {
            ModConfig.SIMD_NOISE = true;
            ModConfig.PARALLEL_STATUS_TASKS = true;
            ModConfig.FAST_SURFACE_RULES = true;

            HolderLookup.Provider registries = VanillaRegistries.createLookup();
            HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
            IdMapper<Holder<Biome>> biomeIdMap = new IdMapper<>();
            biomeLookup.listElements().forEach(biomeIdMap::add);

            Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(biomeIdMap);
            Holder<Biome> plainsBiome = biomeLookup.getOrThrow(Biomes.PLAINS);
            PalettedContainerFactory containerFactory = new PalettedContainerFactory(
                    blockStrategy, Blocks.AIR.defaultBlockState(), null, biomeStrategy, plainsBiome, null);

            HolderGetter<MultiNoiseBiomeSourceParameterList> presetGetter =
                    registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            BiomeSource overworldBiomes = MultiNoiseBiomeSource.createFromPreset(
                    presetGetter.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            HolderGetter<NoiseGeneratorSettings> noiseSettingsGetter =
                    registries.lookupOrThrow(Registries.NOISE_SETTINGS);
            Holder<NoiseGeneratorSettings> overworldSettings =
                    noiseSettingsGetter.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(overworldBiomes, overworldSettings);

            LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(-64, 384);
            WorldGenerationContext surfaceCtx = new WorldGenerationContext(generator, heightAccessor);
            long obfuscatedSeed = BiomeManager.obfuscateSeed(seed);
            StructureManager headlessStructures = new StructureManager(null, new WorldOptions(seed, false, false), null) {
                @Override
                public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
                    return List.of();
                }

                @Override
                public boolean shouldGenerateStructures() {
                    return false;
                }
            };

            List<ChunkPos> warmupSpiral = buildSquareSpiral(0, 0, warmupRadius);
            RandomState warmupRs = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed);
            executeHeadlessSpiral(
                    generator, warmupRs, headlessStructures, heightAccessor, containerFactory,
                    surfaceCtx, obfuscatedSeed, warmupSpiral, Math.max(8, prevWorkers * 4));
            forceGc();

            List<SweepResult> results = new ArrayList<>(workerCounts.length);
            for (int step = 0; step < workerCounts.length; step++) {
                int workers = workerCounts[step];
                ChunkEngine.get().workerPool().setParallelism(workers);
                RandomState rs = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed);
                int cx = FAR_START_CHUNK + step * STEP_CHUNK_STRIDE;
                int cz = FAR_START_CHUNK;
                List<ChunkPos> spiral = buildSquareSpiral(cx, cz, radius);
                int window = Math.max(8, Math.min(64, workers * 4 + 8));

                forceGc();
                long t0 = System.nanoTime();
                long peakHeapMb = executeHeadlessSpiral(
                        generator, rs, headlessStructures, heightAccessor, containerFactory,
                        surfaceCtx, obfuscatedSeed, spiral, window);
                double wallSec = (System.nanoTime() - t0) / 1_000_000_000.0D;
                forceGc();
                long settledHeapMb = usedHeapMb();

                results.add(new SweepResult(
                        workers,
                        spiral.size(),
                        wallSec,
                        spiral.size() / Math.max(1e-6D, wallSec),
                        peakHeapMb,
                        settledHeapMb
                ));
            }

            printReport(seed, warmupRadius, radius, results);
        } finally {
            ModConfig.SIMD_NOISE = prevSimd;
            ModConfig.PARALLEL_STATUS_TASKS = prevParallel;
            ModConfig.FAST_SURFACE_RULES = prevFastRules;
            ChunkEngine.get().workerPool().setParallelism(prevWorkers);
        }
    }

    private static long executeHeadlessSpiral(
            NoiseBasedChunkGenerator generator,
            RandomState rs,
            StructureManager structures,
            LevelHeightAccessor heightAccessor,
            PalettedContainerFactory containerFactory,
            WorldGenerationContext surfaceCtx,
            long obfuscatedSeed,
            List<ChunkPos> spiral,
            int window
    ) {
        var exec = ChunkEngine.get().workerPool().executor();
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>(spiral);
        List<CompletableFuture<ChunkAccess>> active = new ArrayList<>(window);
        long peakHeapMb = usedHeapMb();
        int completed = 0;

        while (!queue.isEmpty() || !active.isEmpty()) {
            while (active.size() < window && !queue.isEmpty()) {
                final ChunkPos pos = queue.poll();
                CompletableFuture<ChunkAccess> f = CompletableFuture.supplyAsync(() -> {
                    ProtoChunk proto = new ProtoChunk(pos, UpgradeData.EMPTY, heightAccessor, containerFactory, null);
                    generator.createBiomes(rs, Blender.empty(), structures, proto).join();
                    proto.setPersistedStatus(ChunkStatus.BIOMES);
                    generator.fillFromNoise(Blender.empty(), rs, structures, proto).join();
                    proto.setPersistedStatus(ChunkStatus.NOISE);
                    generator.buildSurface(
                            proto, surfaceCtx, rs, structures,
                            new BiomeManager(proto, obfuscatedSeed), Blender.empty(), new HashSet<>());
                    return (ChunkAccess) proto;
                }, exec);
                active.add(f);
            }

            boolean progressed = false;
            Iterator<CompletableFuture<ChunkAccess>> it = active.iterator();
            while (it.hasNext()) {
                CompletableFuture<ChunkAccess> f = it.next();
                if (f.isDone()) {
                    f.join();
                    it.remove();
                    completed++;
                    progressed = true;
                }
            }
            if ((completed & 31) == 0) {
                long h = usedHeapMb();
                if (h > peakHeapMb) {
                    peakHeapMb = h;
                }
            }
            if (!progressed) {
                LockSupport.parkNanos(150_000L);
            }
        }
        return Math.max(peakHeapMb, usedHeapMb());
    }

    private static void forceGc() {
        System.gc();
        LockSupport.parkNanos(25_000_000L);
        System.gc();
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
    }

    private static void printReport(
            long seed,
            int warmupRadius,
            int benchRadius,
            List<SweepResult> results
    ) {
        double baseCps = results.isEmpty() ? 1.0D : results.get(0).cps();
        int baseWorkers = results.isEmpty() ? 1 : results.get(0).workers();
        int warmupBlocks = warmupRadius * 16;
        int warmupChunks = (2 * warmupRadius + 1) * (2 * warmupRadius + 1);
        int benchBlocks = benchRadius * 16;
        int benchChunks = (2 * benchRadius + 1) * (2 * benchRadius + 1);
        int centerBlock = FAR_START_CHUNK * 16;

        System.out.println("==========================================================================");
        System.out.println("[CHUNKY-BENCH] PURE-CHUNKY OVERWORLD PIPELINE (Biomes + Noise + Surface)");
        System.out.println(String.format(Locale.ROOT,
                "Seed: %d | Warmup: %d blk (R=%d, %d ch) @ (0,0) | Bench: %d blk (R=%d, %d ch) @ (%d,%d) blk | MaxHeap: %d MB",
                seed, warmupBlocks, warmupRadius, warmupChunks,
                benchBlocks, benchRadius, benchChunks,
                centerBlock, centerBlock,
                Runtime.getRuntime().maxMemory() / (1024L * 1024L)));
        System.out.println("--------------------------------------------------------------------------");
        System.out.println("| Workers | Chunks | Wall (s) |    CPS | Scale | Eff % | PeakMB | SettledMB |");
        System.out.println("|--------:|-------:|---------:|-------:|------:|------:|-------:|----------:|");
        for (SweepResult r : results) {
            double scale = r.cps() / Math.max(1e-6D, baseCps);
            double idealScale = (double) r.workers() / Math.max(1, baseWorkers);
            double effPct = (scale / Math.max(1e-6D, idealScale)) * 100.0D;
            System.out.println(String.format(Locale.ROOT,
                    "| %7d | %6d | %8.2f | %6.1f | %4.2fx | %5.1f | %6d | %9d |",
                    r.workers(),
                    r.chunks(),
                    r.wallSec(),
                    r.cps(),
                    scale,
                    effPct,
                    r.peakHeapMb(),
                    r.settledHeapMb()));
        }
        System.out.println("==========================================================================");
        System.out.flush();
    }
}
