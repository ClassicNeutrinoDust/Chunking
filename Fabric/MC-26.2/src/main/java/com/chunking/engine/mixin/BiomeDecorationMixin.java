package com.chunking.engine.mixin;

import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.FeatureCountTracker;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Eliminates per-chunk biome quart scanning, structure-registry stream grouping, and per-biome
 * feature index remapping inside {@link ChunkGenerator#applyBiomeDecoration}.
 */
@Mixin(ChunkGenerator.class)
public abstract class BiomeDecorationMixin {
    @Unique
    private static final int[] CHUNKING$EMPTY_INTS = new int[0];

    @Unique
    private static final Structure[] CHUNKING$EMPTY_STRUCTURES = new Structure[0];

    @Unique
    private static final int CHUNKING$DECORATION_STEPS = GenerationStep.Decoration.values().length;

    @Unique
    private static final ThreadLocal<BitSet> CHUNKING$STEP_BITS_TL = ThreadLocal.withInitial(() -> new BitSet(128));

    @Unique
    private record CachedStructures(Registry<Structure> registry, int size, Structure[][] byStep) {}

    @Unique
    private static volatile CachedStructures chunking$lastStructures;

    @Unique
    public static void chunking$clearCachedStructures() {
        chunking$lastStructures = null;
    }

    @Shadow
    @Final
    protected BiomeSource biomeSource;

    @Shadow
    @Final
    private Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    @Shadow
    @Final
    private Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;

    @Shadow
    private static BoundingBox getWritableArea(ChunkAccess chunk) {
        throw new AssertionError();
    }

    // Initialized in <init> (mixin field)
    @Unique
    private volatile List<FeatureSorter.StepFeatureData> chunking$cachedStepsRef;

    @Unique
    private volatile ConcurrentHashMap<Holder<Biome>, int[][]> chunking$biomeStepCache;

    @Unique
    private static Structure[][] chunking$getStructuresByStep(Registry<Structure> registry) {
        int size = registry.size();
        CachedStructures cached = chunking$lastStructures;
        if (cached != null && cached.registry() == registry && cached.size() == size) {
            return cached.byStep();
        }
        @SuppressWarnings("unchecked")
        ArrayList<Structure>[] buckets = new ArrayList[CHUNKING$DECORATION_STEPS];
        for (Structure s : registry) {
            int step = s.step().ordinal();
            ArrayList<Structure> list = buckets[step];
            if (list == null) {
                list = new ArrayList<>(4);
                buckets[step] = list;
            }
            list.add(s);
        }
        Structure[][] byStep = new Structure[CHUNKING$DECORATION_STEPS][];
        for (int i = 0; i < CHUNKING$DECORATION_STEPS; i++) {
            ArrayList<Structure> list = buckets[i];
            byStep[i] = (list == null || list.isEmpty())
                    ? CHUNKING$EMPTY_STRUCTURES
                    : list.toArray(CHUNKING$EMPTY_STRUCTURES);
        }
        chunking$lastStructures = new CachedStructures(registry, size, byStep);
        return byStep;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static void chunking$collectSectionBiomes(
            PalettedContainerRO<Holder<Biome>> container,
            ObjectArraySet<Holder<Biome>> out
    ) {
        if (!((Object) container instanceof PalettedContainerAccess<?> rawAcc)) {
            container.getAll(out::add);
            return;
        }
        PalettedDataAccess<Holder<Biome>> data =
                ((PalettedContainerAccess<Holder<Biome>>) rawAcc).chunking$dataAccess();
        if (data == null) {
            container.getAll(out::add);
            return;
        }
        Palette<Holder<Biome>> palette = data.chunking$palette();
        BitStorage storage = data.chunking$storage();
        if (palette == null || storage == null) {
            container.getAll(out::add);
            return;
        }
        int palSize = palette.getSize();
        if (palSize <= 0) {
            return;
        }
        if (storage instanceof ZeroBitStorage || palSize == 1) {
            out.add(palette.valueFor(0));
            return;
        }
        // Scan bit-packed storage into a 64-bit mask rather than blindly reading the palette so
        // stale palette slot-0 entries left behind by vanilla fillBiomesFromNoise are excluded.
        if (palSize <= 64 && storage instanceof SimpleBitStorage) {
            int bits = storage.getBits();
            long[] raw = storage.getRaw();
            if (bits > 0 && bits <= 6 && raw != null) {
                int vpl = 64 / bits;
                long entryMask = (1L << bits) - 1L;
                long allMask = palSize == 64 ? -1L : ((1L << palSize) - 1L);
                long usedMask = 0L;
                int remaining = storage.getSize();
                for (int w = 0; w < raw.length && remaining > 0; w++) {
                    long word = raw[w];
                    int n = Math.min(vpl, remaining);
                    remaining -= n;
                    for (int k = 0; k < n; k++) {
                        usedMask |= 1L << ((int) (word & entryMask));
                        word >>>= bits;
                    }
                    if (usedMask == allMask) {
                        break;
                    }
                }
                while (usedMask != 0L) {
                    int idx = Long.numberOfTrailingZeros(usedMask);
                    usedMask &= usedMask - 1L;
                    if (idx < palSize) {
                        out.add(palette.valueFor(idx));
                    }
                }
                return;
            }
        }
        container.getAll(out::add);
    }

    @Unique
    private ConcurrentHashMap<Holder<Biome>, int[][]> chunking$biomeStepCache(
            List<FeatureSorter.StepFeatureData> steps
    ) {
        ConcurrentHashMap<Holder<Biome>, int[][]> map = this.chunking$biomeStepCache;
        if (map != null && this.chunking$cachedStepsRef == steps) {
            return map;
        }
        synchronized (this) {
            map = this.chunking$biomeStepCache;
            if (map == null || this.chunking$cachedStepsRef != steps) {
                map = new ConcurrentHashMap<>(64);
                this.chunking$biomeStepCache = map;
                this.chunking$cachedStepsRef = steps;
            }
            return map;
        }
    }

    @Unique
    private int[][] chunking$resolveBiomeSteps(
            ConcurrentHashMap<Holder<Biome>, int[][]> cache,
            List<FeatureSorter.StepFeatureData> steps,
            Holder<Biome> biome
    ) {
        int[][] cached = cache.get(biome);
        if (cached != null) {
            return cached;
        }
        int stepCount = steps.size();
        List<HolderSet<PlacedFeature>> biomeFeatures = this.generationSettingsGetter.apply(biome).features();
        int[][] built = new int[stepCount][];
        IntArraySet tmp = new IntArraySet();
        for (int s = 0; s < stepCount; s++) {
            if (s >= biomeFeatures.size()) {
                built[s] = CHUNKING$EMPTY_INTS;
                continue;
            }
            HolderSet<PlacedFeature> set = biomeFeatures.get(s);
            if (set.size() == 0) {
                built[s] = CHUNKING$EMPTY_INTS;
                continue;
            }
            FeatureSorter.StepFeatureData stepData = steps.get(s);
            tmp.clear();
            for (Holder<PlacedFeature> fh : set) {
                tmp.add(stepData.indexMapping().applyAsInt(fh.value()));
            }
            int[] arr = tmp.toIntArray();
            Arrays.sort(arr);
            built[s] = arr;
        }
        int[][] existing = cache.putIfAbsent(biome, built);
        return existing != null ? existing : built;
    }

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"), cancellable = true)
    private void chunking$fastApplyBiomeDecoration(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structureManager,
            CallbackInfo ci
    ) {
        ChunkPos chunkPos = chunk.getPos();
        if (SharedConstants.debugVoidTerrain(chunkPos)) {
            ci.cancel();
            return;
        }
        SectionPos sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());
        BlockPos origin = sectionPos.origin();
        Registry<Structure> structureRegistry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Structure[][] structuresByStep = chunking$getStructuresByStep(structureRegistry);
        List<FeatureSorter.StepFeatureData> steps = this.featuresPerStep.get();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());

        ObjectArraySet<Holder<Biome>> biomes = new ObjectArraySet<>(8);
        int centerX = chunkPos.x();
        int centerZ = chunkPos.z();
        for (int cz = centerZ - 1; cz <= centerZ + 1; cz++) {
            for (int cx = centerX - 1; cx <= centerX + 1; cx++) {
                LevelChunkSection[] sections = level.getChunk(cx, cz).getSections();
                for (int s = 0; s < sections.length; s++) {
                    chunking$collectSectionBiomes(sections[s].getBiomes(), biomes);
                }
            }
        }
        Set<Holder<Biome>> possibleBiomes = this.biomeSource.possibleBiomes();
        biomes.retainAll(possibleBiomes);

        int stepCount = steps.size();
        int biomeCount = biomes.size();
        ConcurrentHashMap<Holder<Biome>, int[][]> stepCache = biomeCount > 0 ? chunking$biomeStepCache(steps) : null;
        int[][] singleBiomeSteps = null;
        int[][][] multiBiomeSteps = null;
        if (biomeCount == 1) {
            singleBiomeSteps = chunking$resolveBiomeSteps(stepCache, steps, biomes.iterator().next());
        } else if (biomeCount > 1) {
            multiBiomeSteps = new int[biomeCount][][];
            int bi = 0;
            for (Holder<Biome> biome : biomes) {
                multiBiomeSteps[bi++] = chunking$resolveBiomeSteps(stepCache, steps, biome);
            }
        }

        try {
            Registry<PlacedFeature> placedFeatureRegistry = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
            int totalSteps = Math.max(CHUNKING$DECORATION_STEPS, stepCount);
            ChunkGenerator self = (ChunkGenerator) (Object) this;
            BoundingBox writableArea = null;

            for (int step = 0; step < totalSteps; step++) {
                int featureIdx = 0;
                if (structureManager.shouldGenerateStructures() && step < structuresByStep.length) {
                    Structure[] stepStructures = structuresByStep[step];
                    for (int si = 0; si < stepStructures.length; si++) {
                        Structure structure = stepStructures[si];
                        random.setFeatureSeed(decorationSeed, featureIdx, step);
                        Supplier<String> desc = () -> structureRegistry.getResourceKey(structure)
                                .map(Object::toString)
                                .orElseGet(structure::toString);
                        try {
                            level.setCurrentlyGenerating(desc);
                            List<StructureStart> starts = structureManager.startsForStructure(sectionPos, structure);
                            for (int st = 0; st < starts.size(); st++) {
                                if (writableArea == null) {
                                    writableArea = getWritableArea(chunk);
                                }
                                starts.get(st).placeInChunk(level, structureManager, self, random, writableArea, chunkPos);
                            }
                        } catch (Exception e) {
                            CrashReport report = CrashReport.forThrowable(e, "Feature placement");
                            report.addCategory("Feature").setDetail("Description", Objects.requireNonNull(desc)::get);
                            throw new ReportedException(report);
                        }
                        featureIdx++;
                    }
                }

                if (step < stepCount && biomeCount > 0) {
                    int[] indices;
                    if (biomeCount == 1) {
                        indices = singleBiomeSteps[step];
                    } else {
                        indices = chunking$mergeStepIndices(multiBiomeSteps, step);
                    }
                    if (indices.length > 0) {
                        List<PlacedFeature> stepFeatures = steps.get(step).features();
                        for (int i = 0; i < indices.length; i++) {
                            int idx = indices[i];
                            PlacedFeature feature = stepFeatures.get(idx);
                            Supplier<String> desc = () -> placedFeatureRegistry.getResourceKey(feature)
                                    .map(Object::toString)
                                    .orElseGet(feature::toString);
                            random.setFeatureSeed(decorationSeed, idx, step);
                            try {
                                level.setCurrentlyGenerating(desc);
                                feature.placeWithBiomeCheck(level, self, random, origin);
                            } catch (Exception e) {
                                CrashReport report = CrashReport.forThrowable(e, "Feature placement");
                                report.addCategory("Feature").setDetail("Description", Objects.requireNonNull(desc)::get);
                                throw new ReportedException(report);
                            }
                        }
                    }
                }
            }

            level.setCurrentlyGenerating(null);
            if (SharedConstants.DEBUG_FEATURE_COUNT) {
                FeatureCountTracker.chunkDecorated(level.getLevel());
            }
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e, "Biome decoration");
            report.addCategory("Generation")
                    .setDetail("CenterX", chunkPos.x())
                    .setDetail("CenterZ", chunkPos.z())
                    .setDetail("Decoration Seed", decorationSeed);
            throw new ReportedException(report);
        }
        ci.cancel();
    }

    @Unique
    private static int[] chunking$mergeStepIndices(int[][][] multiBiomeSteps, int step) {
        BitSet bits = CHUNKING$STEP_BITS_TL.get();
        bits.clear();
        int count = 0;
        boolean hasNegative = false;
        for (int b = 0; b < multiBiomeSteps.length; b++) {
            int[] arr = multiBiomeSteps[b][step];
            for (int i = 0; i < arr.length; i++) {
                int idx = arr[i];
                if (idx < 0) {
                    hasNegative = true;
                    break;
                }
                if (!bits.get(idx)) {
                    bits.set(idx);
                    count++;
                }
            }
            if (hasNegative) {
                break;
            }
        }
        if (hasNegative) {
            bits.clear();
            IntArraySet fallback = new IntArraySet();
            for (int b = 0; b < multiBiomeSteps.length; b++) {
                int[] arr = multiBiomeSteps[b][step];
                for (int i = 0; i < arr.length; i++) {
                    fallback.add(arr[i]);
                }
            }
            int[] out = fallback.toIntArray();
            Arrays.sort(out);
            return out;
        }
        if (count == 0) {
            return CHUNKING$EMPTY_INTS;
        }
        int[] merged = new int[count];
        int k = 0;
        for (int idx = bits.nextSetBit(0); idx >= 0; idx = bits.nextSetBit(idx + 1)) {
            merged[k++] = idx;
        }
        bits.clear();
        return merged;
    }
}
