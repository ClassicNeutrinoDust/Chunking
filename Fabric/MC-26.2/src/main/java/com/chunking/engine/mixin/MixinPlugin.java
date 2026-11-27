package com.chunking.engine.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * With {@code -Dchunking.passive=true} every non-accessor Chunking mixin is skipped (for A/B benchmarking).
 * Also gates overlapping mixins when Lithium, Sodium, VulkanMod, ScalableLux/Starlight, Moonrise,
 * FasterRandom, or Noisium is installed so optimization mods interoperate cleanly.
 */
public final class MixinPlugin implements IMixinConfigPlugin {
    private static final boolean PASSIVE = Boolean.getBoolean("chunking.passive");
    private static final boolean LITHIUM_LOADED = isModLoadedSafe("lithium");
    private static final boolean SODIUM_LOADED = isModLoadedSafe("sodium");
    private static final boolean VULKANMOD_LOADED = isModLoadedSafe("vulkanmod");
    private static final boolean SCALABLELUX_LOADED = isModLoadedSafe("scalablelux") || isModLoadedSafe("starlight");
    private static final boolean MOONRISE_LOADED = isModLoadedSafe("moonrise");
    private static final boolean NOISIUM_LOADED = isModLoadedSafe("noisium");
    private static final boolean FASTER_RANDOM_LOADED = isModLoadedSafe("fasterrandom");
    private static final boolean VOXY_LOADED = isModLoadedSafe("voxy");
    private static final boolean DISTANT_HORIZONS_LOADED = isModLoadedSafe("distanthorizons");

    private static final Set<String> LITHIUM_CONFLICTS = Set.of(
            "ServerChunkCacheGetChunkMixin",
            "PalettedContainerLockMixin",
            "BiomeManagerMixin",
            "WorldGenRegionMixin",
            "ProtoChunkMixin",
            "BlockShapeCacheMixin",
            "VoxelShapeFlagMixin"
    );

    private static final Set<String> FASTER_RANDOM_CONFLICTS = Set.of(
            "LegacyRandomMixin",
            "LegacyPositionalRandomMixin",
            "LevelRandomMixin"
    );

    private static final Set<String> LIGHTING_CONFLICTS = Set.of(
            "LevelLightEngineMixin",
            "LightEngineMixin",
            "SkyLightEngineMixin",
            "BlockLightEngineMixin",
            "LayerLightSectionStorageMixin",
            "SkyLightSectionStorageMixin",
            "DataLayerStorageMapMixin",
            "LightEngineCacheMixin",
            "ClientLevelMixin"
    );

    private static final Set<String> MOONRISE_CONFLICTS = Set.of(
            "StatusTasksMixin", "FullPromotionDrainMixin", "TaskDispatcherMixin", "PriorityQueueMixin",
            "ChunkMapDistanceMixin", "DistanceManagerMixin", "PlayerTicketTrackerMixin", "ServerChunkCacheMixin",
            "ServerChunkCacheGetChunkMixin", "BlockableEventLoopMixin", "ServerLevelMixin",
            "MinecraftServerShutdownMixin", "ChunkHolderMixin", "PlayerChunkSenderMixin", "IntegratedServerMixin",
            "IOWorkerMixin", "IOWorkerPendingStoreMixin", "RegionFileMixin", "RegionFileStorageMixin",
            "RegionFileVersionMixin", "SectionStorageMixin", "SerializableChunkDataMixin", "PoiManagerMixin",
            "DataFixerTypeMixin", "WorldGenRegionMixin", "PalettedContainerLockMixin", "PalettedContainerMixin",
            "PalettedContainerDataMixin", "HeightmapMixin", "LevelChunkSectionMixin", "LevelRandomMixin",
            "ProtoChunkMixin", "BiomeManagerMixin", "OreFeatureMixin", "SimpleBlockFeatureMixin",
            "TreeFeatureMixin", "WorldCarverMixin", "BiomeDecorationMixin", "StructureCheckMixin",
            "StructurePlacementMixin", "JigsawPlacementMixin", "StructureStartMixin", "StructureStateMixin",
            "StructureTemplateMixin", "StructureTemplatePaletteMixin", "StructurePlaceSettingsMixin",
            "StructurePoolElementMixin", "StrongholdPiecesMixin", "StrongholdPieceWeightMixin",
            "StrongholdStairsDownMixin", "StrongholdStartPieceMixin", "NetherFortressBridgePieceMixin",
            "NetherFortressPieceWeightMixin", "NetherFortressStartPieceMixin", "MineshaftRoomMixin",
            "WoodlandMansionSimpleGridMixin", "TransformationMixin", "RandomizedIntStateProviderMixin",
            "ImprovedNoiseMixin", "PerlinNoiseMixin", "BlendedNoiseMixin", "NormalNoiseMixin",
            "NoiseBasedAquiferMixin", "NoiseGeneratorMixin", "NoiseChunkMixin", "NoiseChunkOreInitMixin",
            "NoiseInterpolatorMixin", "NoiseFlatCacheMixin", "NoiseSliceProviderMixin", "NoiseCache2DMixin",
            "NoiseCacheAllInCellMixin", "SurfaceSystemMixin",
            "ClimateRTreeMixin", "ClimateSamplerMixin", "OptionsMixin", "SodiumConfigBuilderMixin",
            "LevelLightEngineMixin", "LightEngineMixin", "SkyLightEngineMixin", "BlockLightEngineMixin",
            "LayerLightSectionStorageMixin", "SkyLightSectionStorageMixin", "DataLayerStorageMapMixin",
            "LightEngineCacheMixin"
    );

    private static final Set<String> NOISIUM_CONFLICTS = Set.of(
            "NoiseGeneratorMixin",
            "NoiseChunkMixin",
            "NoiseChunkOreInitMixin",
            "NoiseInterpolatorMixin",
            "NoiseFlatCacheMixin",
            "NoiseSliceProviderMixin",
            "NoiseCache2DMixin",
            "NoiseCacheAllInCellMixin"
    );

    private static final Set<String> RENDERER_CONFLICTS = Set.of(
            "LevelExtractorMixin",
            "SectionCompilerMixin"
    );

    private static boolean isModLoadedSafe(String modId) {
        try {
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isLithiumLoaded() {
        return LITHIUM_LOADED;
    }

    public static boolean isSodiumLoaded() {
        return SODIUM_LOADED;
    }

    public static boolean isVulkanModLoaded() {
        return VULKANMOD_LOADED;
    }

    public static boolean isNoisiumLoaded() {
        return NOISIUM_LOADED;
    }

    public static boolean isLodModLoaded() {
        return VOXY_LOADED || DISTANT_HORIZONS_LOADED;
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (!PASSIVE
                && isModLoadedSafe("chunky")
                && !MOONRISE_LOADED
                && !com.chunking.engine.config.ModConfig.isClientEnvironment()
                && System.getProperty("chunky.maxWorkingCount") == null) {
            long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            long cap = maxHeapMb <= 2560L ? 32L : 1024L;
            int autoMax = (int) Math.max(32L, Math.min(cap,
                    com.chunking.engine.config.ModConfig.WORLDGEN_WORKERS * 8L));
            System.setProperty("chunky.maxWorkingCount", Integer.toString(autoMax));
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (PASSIVE) {
            return mixinClassName.contains(".accessor.");
        }
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (LITHIUM_LOADED && LITHIUM_CONFLICTS.contains(name)) {
            return false;
        }
        if (FASTER_RANDOM_LOADED && FASTER_RANDOM_CONFLICTS.contains(name)) {
            return false;
        }
        if ((SCALABLELUX_LOADED || MOONRISE_LOADED) && LIGHTING_CONFLICTS.contains(name)) {
            return false;
        }
        if (MOONRISE_LOADED && MOONRISE_CONFLICTS.contains(name)) {
            return false;
        }
        if (NOISIUM_LOADED && NOISIUM_CONFLICTS.contains(name)) {
            return false;
        }
        if ("SodiumConfigBuilderMixin".equals(name)) {
            return SODIUM_LOADED;
        }
        if ((SODIUM_LOADED || VULKANMOD_LOADED) && RENDERER_CONFLICTS.contains(name)) {
            return false;
        }
        return true;
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
