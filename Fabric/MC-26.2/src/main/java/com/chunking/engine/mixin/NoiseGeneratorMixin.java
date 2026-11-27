package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.core.ChunkEngine;
import com.chunking.engine.mixin.accessor.NoiseChunkAccessor;
import com.chunking.engine.access.AquiferAccess;
import com.chunking.engine.access.ClimateParameterListAccess;
import com.chunking.engine.access.ClimateRTreeAccess;
import com.chunking.engine.access.ClimateSamplerAccess;
import com.chunking.engine.access.LevelChunkSectionAccess;
import com.chunking.engine.access.MultiNoiseBiomeSourceAccess;
import com.chunking.engine.access.NoiseSliceEval;
import com.chunking.engine.access.RandomStateAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Routes {@link NoiseBasedChunkGenerator#createBiomes} and {@link NoiseBasedChunkGenerator#fillFromNoise}
 * onto {@link ChunkEngine#workerPool()}, and fast-skips guaranteed sky-air cells in {@code doFill}.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseGeneratorMixin {
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<BlockState[]> CHUNKING$CELL_STATES_TL =
            ThreadLocal.withInitial(() -> new BlockState[1024]);

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<long[]> CHUNKING$CELL_FLUID_MASK_TL =
            ThreadLocal.withInitial(() -> new long[16]);

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<long[]> CHUNKING$COL_DONE_TL =
            ThreadLocal.withInitial(() -> new long[2]);

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    @Final
    private static BlockState AIR;

    @Shadow
    protected abstract NoiseChunk createNoiseChunk(
            ChunkAccess chunk, StructureManager structureManager, Blender blender, RandomState randomState);

    @Shadow
    protected abstract BlockState debugPreliminarySurfaceLevel(
            NoiseChunk noiseChunk, int x, int y, int z, BlockState state);

    @ModifyArg(
            method = { "createBiomes", "fillFromNoise" },
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            ),
            index = 1
    )
    private Executor chunking$useChunkingWorkerPool(Executor original) {
        return ModConfig.PARALLEL_STATUS_TASKS ? ChunkEngine.get().workerPool().inlineIfWorkerExecutor() : original;
    }

    @WrapMethod(method = "getBaseHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I")
    private int chunking$cacheBaseHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor level,
            RandomState randomState,
            Operation<Integer> original
    ) {
        if (!ModConfig.SIMD_NOISE || type == null || level == null
                || !((Object) randomState instanceof RandomStateAccess rsAcc)) {
            return original.call(x, z, type, level, randomState);
        }
        AtomicReferenceArray<RandomStateAccess.BaseHeightEntry> cache = rsAcc.chunking$baseHeightCache();
        int typeOrd = type.ordinal();
        int minY = level.getMinY();
        int height = level.getHeight();
        int cached = RandomStateAccess.lookupBaseHeight(cache, x, z, typeOrd, minY, height);
        if (cached != RandomStateAccess.BASE_HEIGHT_MISS) {
            return cached;
        }
        int computed = original.call(x, z, type, level, randomState);
        RandomStateAccess.storeBaseHeight(cache, x, z, typeOrd, minY, height, computed);
        return computed;
    }

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Holder<Biome>[]> CHUNKING$MODDED_BIOME_BUF_TL =
            ThreadLocal.withInitial(() -> (Holder<Biome>[]) new Holder<?>[0]);

    @Inject(method = "doCreateBiomes", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void chunking$fastColumnBiomes(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunkAccess,
            CallbackInfo ci
    ) {
        if (!ModConfig.SIMD_NOISE
                || (blender != null && !blender.isEmpty())
                || chunkAccess.getBelowZeroRetrogen() != null) {
            return;
        }
        var biomeSource = ((ChunkGenerator) (Object) this).getBiomeSource();
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk(
                chunk -> this.createNoiseChunk(chunk, structureManager, blender, randomState));
        Climate.Sampler sampler = ((NoiseChunkAccessor) noiseChunk).chunking$cachedClimateSampler(
                randomState.router(), this.settings.value().spawnTarget());

        if (biomeSource instanceof net.minecraft.world.level.biome.TheEndBiomeSource endSource
                && com.chunking.engine.world.ModCompat.isVanillaUnmodifiedEndBiomeSource(endSource)) {
            if ((Object) sampler instanceof ClimateSamplerAccess samplerAccess
                    && samplerAccess.chunking$isErosionYInvariant()) {
                Holder<Biome> biome = endSource.getNoiseBiome(
                        chunkAccess.getPos().x() << 2,
                        net.minecraft.core.QuartPos.fromBlock(chunkAccess.getMinY()),
                        chunkAccess.getPos().z() << 2,
                        sampler);
                LevelChunkSection[] sections = chunkAccess.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (!(sections[i].getBiomes() instanceof com.chunking.engine.access.PalettedContainerAccess<?> acc)
                            || !((com.chunking.engine.access.PalettedContainerAccess<Holder<Biome>>) acc).chunking$trySetUniformSingleValue(biome)) {
                        return;
                    }
                }
        ci.cancel();
            }
            return;
        }

        if (com.chunking.engine.world.ModCompat.isVanillaMultiNoiseSource(biomeSource)
                && biomeSource instanceof MultiNoiseBiomeSourceAccess mnAccess) {
            Climate.ParameterList<Holder<Biome>> params = mnAccess.chunking$parameters();
            if (com.chunking.engine.world.ModCompat.isVanillaMultiNoiseParams(params)
                    && (Object) params instanceof ClimateParameterListAccess<?> plAccess) {
                ClimateRTreeAccess<Holder<Biome>> rtree =
                        ((ClimateParameterListAccess<Holder<Biome>>) plAccess).chunking$index();
                if (rtree != null
                        && (Object) sampler instanceof ClimateSamplerAccess samplerAccess
                        && samplerAccess.chunking$fillBiomesColumnFast(chunkAccess, rtree)) {
        ci.cancel();
                    return;
                }
            } else if (com.chunking.engine.world.ModCompat.isTerraBlenderInitializedParams(params)
                    && (Object) sampler instanceof ClimateSamplerAccess samplerAccess
                    && samplerAccess.chunking$fillTerraBlenderBiomesColumnFast(chunkAccess, params)) {
        ci.cancel();
                return;
            }
        }

        // Fast Path 3: Column-major generic modded BiomeSource fast-path
        // (TerraBlender/BoP, custom BiomeSource subclasses, Fabric/TB-modified TheEndBiomeSource)
        LevelChunkSection[] sections = chunkAccess.getSections();
        int secCount = sections.length;
        if (secCount == 0) {
        ci.cancel();
            return;
        }
        int needed = secCount << 6;
        Holder<Biome>[] buf = CHUNKING$MODDED_BIOME_BUF_TL.get();
        if (buf.length < needed) {
            buf = (Holder<Biome>[]) new Holder<?>[needed];
            CHUNKING$MODDED_BIOME_BUF_TL.set(buf);
        }
        int baseQx = net.minecraft.core.QuartPos.fromBlock(chunkAccess.getPos().getMinBlockX());
        int baseQz = net.minecraft.core.QuartPos.fromBlock(chunkAccess.getPos().getMinBlockZ());
        int minQy = net.minecraft.core.QuartPos.fromBlock(chunkAccess.getMinY());
        var localSource = com.chunking.engine.world.ModCompat.threadLocalBiomeSource(biomeSource);
        boolean endColumnInvariant = biomeSource instanceof net.minecraft.world.level.biome.TheEndBiomeSource endSrc
                && com.chunking.engine.world.ModCompat.isVanillaUnmodifiedEndBiomeSource(endSrc)
                && (Object) sampler instanceof ClimateSamplerAccess samplerAccess
                && samplerAccess.chunking$isErosionYInvariant();
        if (endColumnInvariant) {
            for (int lx = 0; lx < 4; lx++) {
                int qx = baseQx + lx;
                for (int lz = 0; lz < 4; lz++) {
                    int qz = baseQz + lz;
                    int colOff = (lz << 2) | lx;
                    Holder<Biome> colBiome = localSource.getNoiseBiome(qx, minQy, qz, sampler);
                    for (int s = 0; s < secCount; s++) {
                        int secBase = s << 6;
                        for (int ly = 0; ly < 4; ly++) {
                            buf[secBase + (ly << 4) + colOff] = colBiome;
                        }
                    }
                }
            }
        } else {
            for (int lx = 0; lx < 4; lx++) {
                int qx = baseQx + lx;
                for (int lz = 0; lz < 4; lz++) {
                    int qz = baseQz + lz;
                    int colOff = (lz << 2) | lx;
                    for (int s = 0; s < secCount; s++) {
                        int secBaseQy = minQy + (s << 2);
                        int secBase = s << 6;
                        for (int ly = 0; ly < 4; ly++) {
                            buf[secBase + (ly << 4) + colOff] = localSource.getNoiseBiome(qx, secBaseQy + ly, qz, sampler);
                        }
                    }
                }
            }
        }
        for (int s = 0; s < secCount; s++) {
            net.minecraft.world.level.chunk.PalettedContainer<Holder<Biome>> container =
                    (net.minecraft.world.level.chunk.PalettedContainer<Holder<Biome>>) (Object) sections[s].getBiomes();
            int secBase = s << 6;
            Holder<Biome> first = buf[secBase];
            boolean uniform = true;
            for (int i = 1; i < 64; i++) {
                if (buf[secBase + i] != first) {
                    uniform = false;
                    break;
                }
            }
            if (uniform && ((com.chunking.engine.access.PalettedContainerAccess<Holder<Biome>>) container)
                    .chunking$trySetUniformSingleValue(first)) {
                continue;
            }
            for (int ly = 0; ly < 4; ly++) {
                int yBase = secBase | (ly << 4);
                for (int lz = 0; lz < 4; lz++) {
                    int zBase = yBase | (lz << 2);
                    container.getAndSetUnchecked(0, ly, lz, buf[zBase]);
                    container.getAndSetUnchecked(1, ly, lz, buf[zBase | 1]);
                    container.getAndSetUnchecked(2, ly, lz, buf[zBase | 2]);
                    container.getAndSetUnchecked(3, ly, lz, buf[zBase | 3]);
                }
            }
        }
        java.util.Arrays.fill(buf, 0, needed, null);
        ci.cancel();
    }

    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void chunking$fastDoFill(
            Blender blender,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunk,
            int cellMinY,
            int cellCountY,
            CallbackInfoReturnable<ChunkAccess> cir
    ) {
        if (!ModConfig.SIMD_NOISE) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(
                c -> this.createNoiseChunk(c, structureManager, blender, randomState));
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();
        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        NoiseChunkAccessor ncAcc = (NoiseChunkAccessor) noiseChunk;
        NoiseSliceEval fastNoise = (NoiseSliceEval) noiseChunk;
        int cw = ncAcc.chunking$getCellWidth();
        int ch = ncAcc.chunking$getCellHeight();
        int cellsX = 16 / cw;
        int cellsZ = 16 / cw;
        double invCw = 1.0D / (double) cw;
        double invCh = 1.0D / (double) ch;
        BlockState defaultBlock = this.settings.value().defaultBlock();
        boolean voidTerrain = SharedConstants.debugVoidTerrain(chunkPos);
        boolean defaultBlocksMotion = !voidTerrain && defaultBlock != AIR && defaultBlock.blocksMotion();
        boolean bulkSolidEligible = defaultBlocksMotion
                && !SharedConstants.DEBUG_ORE_VEINS
                && !SharedConstants.DEBUG_AQUIFERS
                && !defaultBlock.isRandomlyTicking()
                && defaultBlock.getFluidState().isEmpty()
                && ch <= 16;
        AquiferAccess aqAccess = (!SharedConstants.DEBUG_AQUIFERS && aquifer instanceof AquiferAccess a)
                ? a : null;
        int skipAqY = (aqAccess != null) ? aqAccess.chunking$skipSamplingAboveY() : Integer.MAX_VALUE;
        boolean barrierNeedsChunk = (aqAccess == null) || aqAccess.chunking$barrierNeedsNoiseChunk();
        boolean canTrackColBits = ((cw == 4 || cw == 8) && !voidTerrain);
        long fullColMask = (cw == 8) ? -1L : 0xFFFFL;
        int cwShift = (cw == 8) ? 3 : 2;
        long[] colDoneOut = CHUNKING$COL_DONE_TL.get();

        for (int cellX = 0; cellX < cellsX; cellX++) {
            noiseChunk.advanceCellX(cellX);
            int baseCellX = minBlockX + cellX * cw;
            int localBaseX = baseCellX & 15;
            for (int cellZ = 0; cellZ < cellsZ; cellZ++) {
                int baseCellZ = minBlockZ + cellZ * cw;
                int localBaseZ = baseCellZ & 15;
                int sectionIdx = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(sectionIdx);
                long colOceanDone = 0L;
                long colWorldDone = 0L;

                for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                    noiseChunk.selectCellYZ(cellY, cellZ);
                    if (fastNoise.chunking$isCurrentCellGuaranteedAir()) {
                        continue;
                    }
                    int cellBaseY = (cellMinY + cellY) * ch;
                    if (fastNoise.chunking$isCurrentCellAllNonPositive() && aqAccess != null) {
                        BlockState uFluid = aqAccess.chunking$getUniformBoxFluid(baseCellX, cellBaseY, baseCellZ, cw, ch);
                        int fillRows = 0;
                        if (uFluid == AIR) {
                            continue;
                        } else if (uFluid != null) {
                            fillRows = ch;
                        } else if (aqAccess.chunking$isPreparedAllEqual()) {
                            BlockState ft = aqAccess.chunking$preparedFluidType();
                            if (ft == AIR) {
                                continue;
                            }
                            int rows = aqAccess.chunking$preparedFluidLevel() - cellBaseY;
                            if (rows > 0 && rows < ch) {
                                uFluid = ft;
                                fillRows = rows;
                            }
                        }
                        if (uFluid != null && fillRows > 0 && !voidTerrain && ch <= 16) {
                            int botSecIdx = chunk.getSectionIndex(cellBaseY);
                            int topY = cellBaseY + fillRows - 1;
                            if (botSecIdx == chunk.getSectionIndex(cellBaseY + ch - 1)) {
                                if (sectionIdx != botSecIdx) {
                                    sectionIdx = botSecIdx;
                                    section = chunk.getSection(botSecIdx);
                                }
                                ((LevelChunkSectionAccess) section).chunking$fillUniformSolidCell(
                                        localBaseX, cellBaseY & 15, localBaseZ, cw, fillRows, uFluid);
                                boolean uMotion = uFluid.blocksMotion();
                                if (!canTrackColBits || (colWorldDone != fullColMask) || (uMotion && colOceanDone != fullColMask)) {
                                    chunking$updateUniformHeightmaps(
                                            oceanFloor, worldSurface, uFluid, uMotion,
                                            localBaseX, localBaseZ, topY, cw, cwShift,
                                            canTrackColBits, colOceanDone, colWorldDone, colDoneOut);
                                    colOceanDone = colDoneOut[0];
                                    colWorldDone = colDoneOut[1];
                                }
                                continue;
                            }
                        }
                    }
                    if (bulkSolidEligible && fastNoise.chunking$isCurrentCellGuaranteedSolidDefault()) {
                        int botSecIdx = chunk.getSectionIndex(cellBaseY);
                        int topY = cellBaseY + ch - 1;
                        if (botSecIdx == chunk.getSectionIndex(topY)) {
                            if (sectionIdx != botSecIdx) {
                                sectionIdx = botSecIdx;
                                section = chunk.getSection(botSecIdx);
                            }
                            ((LevelChunkSectionAccess) section).chunking$fillUniformSolidCell(
                                    localBaseX, cellBaseY & 15, localBaseZ, cw, ch, defaultBlock);
                            if (!canTrackColBits || (colOceanDone & colWorldDone) != fullColMask) {
                                chunking$updateUniformHeightmaps(
                                        oceanFloor, worldSurface, defaultBlock, true,
                                        localBaseX, localBaseZ, topY, cw, cwShift,
                                        canTrackColBits, colOceanDone, colWorldDone, colDoneOut);
                                colOceanDone = colDoneOut[0];
                                colWorldDone = colDoneOut[1];
                            }
                            continue;
                        }
                    }

                    boolean oreInactive = fastNoise.chunking$isCurrentCellOreInactive();
                    boolean cellAllPositive = fastNoise.chunking$isCurrentCellAllPositive();
                    double[] fullVals = (!SharedConstants.DEBUG_AQUIFERS)
                            ? fastNoise.chunking$getFullDensityCellValues() : null;
                    boolean fastOre = false;
                    if (!oreInactive) {
                        if (fullVals != null && fastNoise.chunking$canEvalFastOre()) {
                            fastOre = true;
                        } else {
                            fullVals = null;
                        }
                    }
                    BlockState uMixedFluid = (!cellAllPositive && fullVals != null && aqAccess != null)
                            ? aqAccess.chunking$getUniformBoxFluid(baseCellX, cellBaseY, baseCellZ, cw, ch)
                            : null;
                    boolean aqAllEqual = (!cellAllPositive && fullVals != null && uMixedFluid == null && aqAccess != null
                            && aqAccess.chunking$isPreparedAllEqual());
                    BlockState effMixedFluid = (uMixedFluid != null) ? uMixedFluid
                            : (aqAllEqual ? aqAccess.chunking$preparedFluidType() : null);
                    int effFluidMaxY = (uMixedFluid != null) ? Integer.MAX_VALUE
                            : (aqAllEqual ? aqAccess.chunking$preparedFluidLevel() : Integer.MIN_VALUE);
                    boolean hasPreparedAq = (!cellAllPositive && fullVals != null && effMixedFluid == null && aqAccess != null
                            && aqAccess.chunking$hasPreparedCell());
                    int botSecIdx = chunk.getSectionIndex(cellBaseY);
                    int cellTopY = cellBaseY + ch - 1;
                    boolean singleSection = bulkSolidEligible && ch <= 16 && botSecIdx == chunk.getSectionIndex(cellTopY);
                    if (fullVals != null && effMixedFluid != null && singleSection && !fastOre) {
                        if (sectionIdx != botSecIdx) {
                            sectionIdx = botSecIdx;
                            section = chunk.getSection(botSecIdx);
                        }
                        boolean mOk = ((LevelChunkSectionAccess) section).chunking$fillMixedDensityCell(
                                localBaseX, cellBaseY & 15, localBaseZ, cellBaseY, cw, ch,
                                fullVals, defaultBlock, effMixedFluid, effFluidMaxY);
                        if (mOk) {
                            if (!canTrackColBits || (colOceanDone & colWorldDone) != fullColMask) {
                                chunking$updateMixedHeightmaps(
                                        oceanFloor, worldSurface, fullVals, defaultBlock, effMixedFluid, effFluidMaxY,
                                        localBaseX, localBaseZ, cellTopY, cw, ch, cwShift, canTrackColBits, colOceanDone, colWorldDone, colDoneOut);
                                colOceanDone = colDoneOut[0];
                                colWorldDone = colDoneOut[1];
                            }
                            continue;
                        }
                    }

                    int totalVoxels = ch * cw * cw;
                    if (singleSection && totalVoxels <= 1024) {
                        if (sectionIdx != botSecIdx) {
                            sectionIdx = botSecIdx;
                            section = chunk.getSection(botSecIdx);
                        }
                        if (chunking$evalAndWriteCellBuffered(
                                chunk, section, noiseChunk, fastNoise, aquifer, aqAccess, skipAqY, barrierNeedsChunk,
                                oceanFloor, worldSurface, mutablePos,
                                fullVals, cellAllPositive, fastOre, effMixedFluid, effFluidMaxY, hasPreparedAq,
                                defaultBlock, defaultBlocksMotion, baseCellX, cellBaseY, baseCellZ, localBaseX, localBaseZ,
                                cw, ch, cwShift, fullColMask, invCw, invCh, canTrackColBits, colOceanDone, colWorldDone, colDoneOut)) {
                            colOceanDone = colDoneOut[0];
                            colWorldDone = colDoneOut[1];
                            continue;
                        }
                    }

                    chunking$fillCellFallback(
                            chunk, noiseChunk, fastNoise, aquifer, aqAccess, skipAqY,
                            oceanFloor, worldSurface, mutablePos,
                            fullVals, fastOre, uMixedFluid, hasPreparedAq,
                            defaultBlock, defaultBlocksMotion, voidTerrain, baseCellX, cellBaseY, baseCellZ,
                            cw, ch, cwShift, invCw, invCh, canTrackColBits, colOceanDone, colWorldDone, colDoneOut, sectionIdx, section);
                    sectionIdx = chunk.getSectionIndex(cellBaseY);
                    section = chunk.getSection(sectionIdx);
                    colOceanDone = colDoneOut[0];
                    colWorldDone = colDoneOut[1];
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
        if (MixinPlugin.isLithiumLoaded()) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection sec = sections[i];
                if (sec != null && !sec.hasOnlyAir()) {
                    sec.recalcBlockCounts();
                }
            }
        }
        cir.setReturnValue(chunk);
    }

    @org.spongepowered.asm.mixin.Unique
    private static void chunking$updateUniformHeightmaps(
            Heightmap oceanFloor,
            Heightmap worldSurface,
            BlockState state,
            boolean blocksMotion,
            int localBaseX,
            int localBaseZ,
            int topY,
            int cw,
            int cwShift,
            boolean canTrackColBits,
            long colOceanDone,
            long colWorldDone,
            long[] outColDone
    ) {
        for (int inX = 0; inX < cw; inX++) {
            int lx = localBaseX + inX;
            for (int inZ = 0; inZ < cw; inZ++) {
                int lz = localBaseZ + inZ;
                long bit = 1L << ((inZ << cwShift) | inX);
                if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                    oceanFloor.update(lx, topY, lz, state);
                    if (blocksMotion) colOceanDone |= bit;
                }
                if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                    worldSurface.update(lx, topY, lz, state);
                    colWorldDone |= bit;
                }
            }
        }
        outColDone[0] = colOceanDone;
        outColDone[1] = colWorldDone;
    }

    @org.spongepowered.asm.mixin.Unique
    private void chunking$updateMixedHeightmaps(
            Heightmap oceanFloor,
            Heightmap worldSurface,
            double[] fullVals,
            BlockState defaultBlock,
            BlockState uMixedFluid,
            int fluidMaxExclusiveY,
            int localBaseX,
            int localBaseZ,
            int cellTopY,
            int cw,
            int ch,
            int cwShift,
            boolean canTrackColBits,
            long colOceanDone,
            long colWorldDone,
            long[] outColDone
    ) {
        int strideY = cw * cw;
        boolean fluidNonAir = uMixedFluid != AIR;
        boolean fluidMotion = fluidNonAir && uMixedFluid.blocksMotion();
        for (int inX = 0; inX < cw; inX++) {
            int lx = localBaseX + inX;
            int colOff = inX * cw;
            for (int inZ = 0; inZ < cw; inZ++) {
                int lz = localBaseZ + inZ;
                long bit = 1L << ((inZ << cwShift) | inX);
                if (canTrackColBits && ((colOceanDone & colWorldDone) & bit) != 0L) {
                    continue;
                }
                int idx = colOff + inZ;
                for (int dyDown = 0; dyDown < ch; dyDown++, idx += strideY) {
                    int by = cellTopY - dyDown;
                    if (fullVals[idx] > 0.0D) {
                        if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                            oceanFloor.update(lx, by, lz, defaultBlock);
                            colOceanDone |= bit;
                        }
                        if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                            worldSurface.update(lx, by, lz, defaultBlock);
                            colWorldDone |= bit;
                        }
                        break;
                    } else if (fluidNonAir && by < fluidMaxExclusiveY) {
                        if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                            oceanFloor.update(lx, by, lz, uMixedFluid);
                            if (fluidMotion) colOceanDone |= bit;
                        }
                        if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                            worldSurface.update(lx, by, lz, uMixedFluid);
                            colWorldDone |= bit;
                        }
                        if (canTrackColBits && ((colOceanDone & colWorldDone) & bit) != 0L) {
                            break;
                        }
                    }
                }
            }
        }
        outColDone[0] = colOceanDone;
        outColDone[1] = colWorldDone;
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean chunking$evalAndWriteCellBuffered(
            ChunkAccess chunk,
            LevelChunkSection section,
            NoiseChunk noiseChunk,
            NoiseSliceEval fastNoise,
            Aquifer aquifer,
            AquiferAccess aqAccess,
            int skipAqY,
            boolean barrierNeedsChunk,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            BlockPos.MutableBlockPos mutablePos,
            double[] fullVals,
            boolean cellAllPositive,
            boolean fastOre,
            BlockState uMixedFluid,
            int fluidMaxExclusiveY,
            boolean hasPreparedAq,
            BlockState defaultBlock,
            boolean defaultBlocksMotion,
            int baseCellX,
            int cellBaseY,
            int baseCellZ,
            int localBaseX,
            int localBaseZ,
            int cw,
            int ch,
            int cwShift,
            long fullColMask,
            double invCw,
            double invCh,
            boolean canTrackColBits,
            long colOceanDone,
            long colWorldDone,
            long[] outColDone
    ) {
        BlockState[] cellBuf = CHUNKING$CELL_STATES_TL.get();
        if (cellAllPositive && fullVals != null && fastOre) {
            int cellIdx = 0;
            boolean skipHm = canTrackColBits && colOceanDone == fullColMask && colWorldDone == fullColMask;
            for (int inY = ch - 1; inY >= 0; inY--) {
                int by = cellBaseY + inY;
                for (int inX = 0; inX < cw; inX++) {
                    int bx = baseCellX + inX;
                    int localX = bx & 15;
                    for (int inZ = 0; inZ < cw; inZ++) {
                        int bz = baseCellZ + inZ;
                        BlockState state = fastNoise.chunking$evalOreVeinAt(
                                bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock);
                        cellBuf[cellIdx++] = state;
                        if (!skipHm) {
                            int localZ = bz & 15;
                            long bit = 1L << ((inZ << cwShift) | inX);
                            if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                                oceanFloor.update(localX, by, localZ, state);
                                colOceanDone |= bit;
                            }
                            if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                                worldSurface.update(localX, by, localZ, state);
                                colWorldDone |= bit;
                            }
                        }
                    }
                }
            }
            if (((LevelChunkSectionAccess) section).chunking$writeStateCell(
                    localBaseX, cellBaseY & 15, localBaseZ, cw, ch, cellBuf, defaultBlock)) {
                outColDone[0] = colOceanDone;
                outColDone[1] = colWorldDone;
                return true;
            }
            return false;
        }
        if (hasPreparedAq && fullVals != null && cw == 4 && ch <= 16) {
            long[] fluidMask = CHUNKING$CELL_FLUID_MASK_TL.get();
            if (aqAccess.chunking$fillCellBuf(
                    noiseChunk, fullVals, cellBuf, fluidMask,
                    baseCellX, cellBaseY, baseCellZ, cw, ch, invCw, invCh, barrierNeedsChunk)) {
                if (!fastOre && canTrackColBits && colOceanDone == fullColMask && colWorldDone == fullColMask) {
                    int total = ch << (cwShift << 1);
                    for (int i = 0; i < total; i++) {
                        if (fullVals[i] > 0.0D || cellBuf[i] == null) {
                            cellBuf[i] = defaultBlock;
                        } else if (cellBuf[i] == AIR) {
                            cellBuf[i] = null;
                        }
                    }
                    int maskWords = (total + 63) >>> 6;
                    for (int w = 0; w < maskWords; w++) {
                        long m = fluidMask[w];
                        while (m != 0L) {
                            int b = Long.numberOfTrailingZeros(m);
                            int cIdx = (w << 6) | b;
                            int inY = ch - 1 - (cIdx >>> 4);
                            int inX = (cIdx >>> 2) & 3;
                            int inZ = cIdx & 3;
                            mutablePos.set(baseCellX + inX, cellBaseY + inY, baseCellZ + inZ);
                            chunk.markPosForPostProcessing(mutablePos);
                            m &= m - 1L;
                        }
                    }
                } else {
                    int cellIdx = 0;
                    for (int inY = ch - 1; inY >= 0; inY--) {
                        int by = cellBaseY + inY;
                        for (int inX = 0; inX < 4; inX++) {
                            int bx = baseCellX + inX;
                            int localX = bx & 15;
                            for (int inZ = 0; inZ < 4; inZ++) {
                                int bz = baseCellZ + inZ;
                                int localZ = bz & 15;
                                long bit = 1L << ((inZ << cwShift) | inX);
                                BlockState state;
                                if (fullVals[cellIdx] > 0.0D) {
                                    state = fastOre
                                            ? fastNoise.chunking$evalOreVeinAt(
                                                    bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                            : defaultBlock;
                                    cellBuf[cellIdx++] = state;
                                    if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                                        oceanFloor.update(localX, by, localZ, state);
                                        colOceanDone |= bit;
                                    }
                                    if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                                        worldSurface.update(localX, by, localZ, state);
                                        colWorldDone |= bit;
                                    }
                                    continue;
                                }
                                state = cellBuf[cellIdx];
                                if (state == AIR) {
                                    cellBuf[cellIdx++] = null;
                                    continue;
                                }
                                if (state == null) {
                                    state = fastOre
                                            ? fastNoise.chunking$evalOreVeinAt(
                                                    bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                            : defaultBlock;
                                    cellBuf[cellIdx++] = state;
                                    if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                                        oceanFloor.update(localX, by, localZ, state);
                                        colOceanDone |= bit;
                                    }
                                    if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                                        worldSurface.update(localX, by, localZ, state);
                                        colWorldDone |= bit;
                                    }
                                    continue;
                                }
                                if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                                    oceanFloor.update(localX, by, localZ, state);
                                    if (state == defaultBlock && defaultBlocksMotion) colOceanDone |= bit;
                                }
                                if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                                    worldSurface.update(localX, by, localZ, state);
                                    if (state == defaultBlock) colWorldDone |= bit;
                                }
                                if ((fluidMask[cellIdx >> 6] & (1L << (cellIdx & 63))) != 0L) {
                                    mutablePos.set(bx, by, bz);
                                    chunk.markPosForPostProcessing(mutablePos);
                                }
                                cellIdx++;
                            }
                        }
                    }
                }
                if (((LevelChunkSectionAccess) section).chunking$writeStateCell(
                        localBaseX, cellBaseY & 15, localBaseZ, cw, ch, cellBuf, defaultBlock)) {
                    outColDone[0] = colOceanDone;
                    outColDone[1] = colWorldDone;
                    return true;
                }
                return false;
            }
        }
        int cellIdx = 0;
        for (int inY = ch - 1; inY >= 0; inY--) {
            int by = cellBaseY + inY;
            boolean yUpdated = false;
            for (int inX = 0; inX < cw; inX++) {
                int bx = baseCellX + inX;
                int localX = bx & 15;
                boolean xUpdated = false;
                for (int inZ = 0; inZ < cw; inZ++) {
                    int bz = baseCellZ + inZ;
                    int localZ = bz & 15;
                    long bit = 1L << ((inZ << cwShift) | inX);
                    BlockState state;
                    boolean ranAquifer = false;
                    if (fullVals != null) {
                        double dens = fullVals[cellIdx];
                        if (dens > 0.0D) {
                            state = fastOre
                                    ? fastNoise.chunking$evalOreVeinAt(
                                            bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                    : defaultBlock;
                            cellBuf[cellIdx++] = state;
                            if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                                oceanFloor.update(localX, by, localZ, state);
                                colOceanDone |= bit;
                            }
                            if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                                worldSurface.update(localX, by, localZ, state);
                                colWorldDone |= bit;
                            }
                            continue;
                        }
                        if (by > skipAqY && aqAccess != null) {
                            state = aqAccess.chunking$globalFluidAt(bx, by, bz);
                            if (state == AIR) {
                                cellBuf[cellIdx++] = null;
                                continue;
                            }
                        } else if (uMixedFluid == AIR) {
                            cellBuf[cellIdx++] = null;
                            continue;
                        } else if (uMixedFluid != null) {
                            if (by >= fluidMaxExclusiveY || uMixedFluid == AIR) {
                                cellBuf[cellIdx++] = null;
                                continue;
                            }
                            state = uMixedFluid;
                        } else if (hasPreparedAq) {
                            if (barrierNeedsChunk) {
                                if (!yUpdated) {
                                    noiseChunk.updateForY(by, (double) inY * invCh);
                                    yUpdated = true;
                                }
                                if (!xUpdated) {
                                    noiseChunk.updateForX(bx, (double) inX * invCw);
                                    xUpdated = true;
                                }
                                noiseChunk.updateForZ(bz, (double) inZ * invCw);
                            }
                            ranAquifer = true;
                            state = aqAccess.chunking$computeVoxelFromCell(noiseChunk, bx, by, bz, dens);
                            if (state == null) {
                                state = fastOre
                                        ? fastNoise.chunking$evalOreVeinAt(
                                                bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                        : defaultBlock;
                            }
                        } else {
                            if (!yUpdated) {
                                noiseChunk.updateForY(by, (double) inY * invCh);
                                yUpdated = true;
                            }
                            if (!xUpdated) {
                                noiseChunk.updateForX(bx, (double) inX * invCw);
                                xUpdated = true;
                            }
                            noiseChunk.updateForZ(bz, (double) inZ * invCw);
                            ranAquifer = true;
                            state = aquifer.computeSubstance(noiseChunk, dens);
                            if (state == null) {
                                state = fastOre
                                        ? fastNoise.chunking$evalOreVeinAt(
                                                bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                        : defaultBlock;
                            }
                        }
                    } else {
                        if (!yUpdated) {
                            noiseChunk.updateForY(by, (double) inY * invCh);
                            yUpdated = true;
                        }
                        if (!xUpdated) {
                            noiseChunk.updateForX(bx, (double) inX * invCw);
                            xUpdated = true;
                        }
                        noiseChunk.updateForZ(bz, (double) inZ * invCw);
                        ranAquifer = true;
                        state = fastNoise.chunking$getInterpolatedStateFast();
                        if (state == null) {
                            state = defaultBlock;
                        }
                    }
                    if (state == AIR) {
                        cellBuf[cellIdx++] = null;
                    } else {
                        cellBuf[cellIdx++] = state;
                        if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                            oceanFloor.update(localX, by, localZ, state);
                            if (state == defaultBlock && defaultBlocksMotion) colOceanDone |= bit;
                        }
                        if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                            worldSurface.update(localX, by, localZ, state);
                            if (state == defaultBlock) colWorldDone |= bit;
                        }
                        if (ranAquifer && aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                            mutablePos.set(bx, by, bz);
                            chunk.markPosForPostProcessing(mutablePos);
                        }
                    }
                }
            }
        }
        if (((LevelChunkSectionAccess) section).chunking$writeStateCell(
                localBaseX, cellBaseY & 15, localBaseZ, cw, ch, cellBuf, defaultBlock)) {
            outColDone[0] = colOceanDone;
            outColDone[1] = colWorldDone;
            return true;
        }
        return false;
    }

    @org.spongepowered.asm.mixin.Unique
    private void chunking$fillCellFallback(
            ChunkAccess chunk,
            NoiseChunk noiseChunk,
            NoiseSliceEval fastNoise,
            Aquifer aquifer,
            AquiferAccess aqAccess,
            int skipAqY,
            Heightmap oceanFloor,
            Heightmap worldSurface,
            BlockPos.MutableBlockPos mutablePos,
            double[] fullVals,
            boolean fastOre,
            BlockState uMixedFluid,
            boolean hasPreparedAq,
            BlockState defaultBlock,
            boolean defaultBlocksMotion,
            boolean voidTerrain,
            int baseCellX,
            int cellBaseY,
            int baseCellZ,
            int cw,
            int ch,
            int cwShift,
            double invCw,
            double invCh,
            boolean canTrackColBits,
            long colOceanDone,
            long colWorldDone,
            long[] outColDone,
            int sectionIdx,
            LevelChunkSection section
    ) {
        int cellIdx = 0;
        for (int inY = ch - 1; inY >= 0; inY--) {
            int by = cellBaseY + inY;
            int localY = by & 15;
            int nextSecIdx = chunk.getSectionIndex(by);
            if (sectionIdx != nextSecIdx) {
                sectionIdx = nextSecIdx;
                section = chunk.getSection(nextSecIdx);
            }
            noiseChunk.updateForY(by, (double) inY * invCh);
            for (int inX = 0; inX < cw; inX++) {
                int bx = baseCellX + inX;
                int localX = bx & 15;
                noiseChunk.updateForX(bx, (double) inX * invCw);
                for (int inZ = 0; inZ < cw; inZ++) {
                    int bz = baseCellZ + inZ;
                    int localZ = bz & 15;
                    long bit = 1L << ((inZ << cwShift) | inX);
                    BlockState state;
                    if (fullVals != null) {
                        double dens = fullVals[cellIdx];
                        if (dens > 0.0D) {
                            state = fastOre
                                    ? fastNoise.chunking$evalOreVeinAt(
                                            bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                    : defaultBlock;
                            cellIdx++;
                            if (!voidTerrain) {
                                section.setBlockState(localX, localY, localZ, state, false);
                                if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                                    oceanFloor.update(localX, by, localZ, state);
                                    if (defaultBlocksMotion) colOceanDone |= bit;
                                }
                                if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                                    worldSurface.update(localX, by, localZ, state);
                                    if (!state.isAir()) colWorldDone |= bit;
                                }
                            }
                            continue;
                        }
                        if (by > skipAqY && aqAccess != null) {
                            state = aqAccess.chunking$globalFluidAt(bx, by, bz);
                            cellIdx++;
                            if (state == AIR) {
                                continue;
                            }
                        } else if (uMixedFluid == AIR) {
                            cellIdx++;
                            continue;
                        } else if (uMixedFluid != null) {
                            cellIdx++;
                            state = uMixedFluid;
                        } else {
                            noiseChunk.updateForZ(bz, (double) inZ * invCw);
                            state = hasPreparedAq
                                    ? aqAccess.chunking$computeVoxelFromCell(noiseChunk, bx, by, bz, dens)
                                    : aquifer.computeSubstance(noiseChunk, dens);
                            if (state == null) {
                                state = fastOre
                                        ? fastNoise.chunking$evalOreVeinAt(
                                                bx, by, bz, inX, inY, inZ, cellIdx, defaultBlock)
                                        : defaultBlock;
                            }
                            cellIdx++;
                        }
                    } else {
                        noiseChunk.updateForZ(bz, (double) inZ * invCw);
                        state = fastNoise.chunking$getInterpolatedStateFast();
                        if (state == null) {
                            state = defaultBlock;
                        }
                    }
                    state = this.debugPreliminarySurfaceLevel(noiseChunk, bx, by, bz, state);
                    if (state != AIR && !voidTerrain) {
                        section.setBlockState(localX, localY, localZ, state, false);
                        if (!canTrackColBits || (colOceanDone & bit) == 0L) {
                            oceanFloor.update(localX, by, localZ, state);
                            if (state == defaultBlock && defaultBlocksMotion) colOceanDone |= bit;
                        }
                        if (!canTrackColBits || (colWorldDone & bit) == 0L) {
                            worldSurface.update(localX, by, localZ, state);
                            if (state == defaultBlock) colWorldDone |= bit;
                        }
                        if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                            mutablePos.set(bx, by, bz);
                            chunk.markPosForPostProcessing(mutablePos);
                        }
                    }
                }
            }
        }
        outColDone[0] = colOceanDone;
        outColDone[1] = colWorldDone;
    }
}
