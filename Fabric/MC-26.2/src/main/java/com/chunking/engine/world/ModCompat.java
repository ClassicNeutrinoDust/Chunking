package com.chunking.engine.world;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Set;
import com.chunking.engine.access.BeardifierAccess;
import com.chunking.engine.access.ClimateParameterListAccess;
import com.chunking.engine.access.ClimateRTreeAccess;
import com.chunking.engine.access.MultiNoiseBiomeSourceAccess;

/**
 * Zero-allocation runtime compatibility detection for external worldgen mods
 * (TerraBlender, Fabric Biome API End overrides, Moog's Structure Lib, custom
 * {@link net.minecraft.world.level.levelgen.DensityFunction}s, and custom {@link BiomeSource}s).
 */
public final class ModCompat {
    private static final ClassValue<Boolean> VANILLA_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.");
        }
    };

    private static final ClassValue<Boolean> VANILLA_DF_INNER = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.world.level.levelgen.DensityFunctions$");
        }
    };

    private static final Class<?> TB_EXTENDED_PARAM_LIST;
    private static final MethodHandle TB_IS_INITIALIZED;
    private static final MethodHandle TB_PARAM_CLONE;
    private static final MethodHandle TB_RECREATE_UNIQUENESS;
    private static final MethodHandle TB_GET_UNIQUENESS;
    private static final MethodHandle TB_GET_TREE;
    private static final MethodHandle TB_SOURCE_CLONE;
    private static final MethodHandle TB_SET_PARAMETERS;
    private static final ResourceKey<Biome> TB_DEFERRED_KEY;
    private static volatile int sessionGeneration = 0;

    private static final class GenCache<K, V> {
        int gen = sessionGeneration;
        final IdentityHashMap<K, V> map = new IdentityHashMap<>(4);

        IdentityHashMap<K, V> currentMap() {
            int g = sessionGeneration;
            if (this.gen != g) {
                this.map.clear();
                this.gen = g;
            }
            return this.map;
        }
    }

    private static final ThreadLocal<GenCache<BiomeSource, BiomeSource>> TB_TL_SOURCE_CACHE =
            ThreadLocal.withInitial(GenCache::new);
    private static final ThreadLocal<GenCache<Climate.ParameterList<?>, Climate.ParameterList<?>>> TB_TL_PARAMS_CACHE =
            ThreadLocal.withInitial(GenCache::new);

    public static void resetSession() {
        sessionGeneration++;
    }

    private static final ClassValue<Boolean> STATELESS_MOD_DF = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            return "dev.worldgen.tectonic.worldgen.densityfunction.ConfigConstant".equals(name)
                    || "dev.worldgen.tectonic.worldgen.densityfunction.Invert".equals(name)
                    || "dev.worldgen.tectonic.worldgen.densityfunction.ConfigClamp".equals(name)
                    || "dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise".equals(name);
        }
    };

    private static final ClassValue<Boolean> Y_INVARIANT_MOD_DF = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            return "dev.worldgen.tectonic.worldgen.densityfunction.ConfigConstant".equals(name)
                    || "dev.worldgen.tectonic.worldgen.densityfunction.Invert".equals(name)
                    || "dev.worldgen.tectonic.worldgen.densityfunction.ConfigClamp".equals(name);
        }
    };

    public static boolean isYInvariantModDensityFunction(Class<?> cls) {
        return cls != null && Y_INVARIANT_MOD_DF.get(cls);
    }

    private static final Class<?> TB_EXTENDED_END_SOURCE;
    private static final Field TB_END_INITIALIZED_FIELD;

    private static final Set<?> FAPI_END_ADDED_BIOMES;

    private static final Class<?> MOOG_ENHANCED_BEARDIFIER;
    private static final MethodHandle MOOG_GET_PIECES;
    private static final MethodHandle MOOG_GET_JUNCTIONS;

    private static final Class<?> BE_TARGET_CHECKER;
    private static final MethodHandle BE_IS_TARGET;

    static {
        Class<?> tbParamCls = null;
        MethodHandle tbInitMh = null;
        MethodHandle tbParamCloneMh = null;
        MethodHandle tbRecreateMh = null;
        MethodHandle tbGetUniqMh = null;
        MethodHandle tbGetTreeMh = null;
        MethodHandle tbSourceCloneMh = null;
        MethodHandle tbSetParamsMh = null;
        ResourceKey<Biome> tbDeferredKey = null;
        try {
            tbParamCls = Class.forName("terrablender.worldgen.IExtendedParameterList");
            tbInitMh = MethodHandles.publicLookup().findVirtual(
                    tbParamCls, "isInitialized", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            tbParamCloneMh = MethodHandles.publicLookup().findVirtual(
                    tbParamCls, "clone", MethodType.methodType(Climate.ParameterList.class));
            tbRecreateMh = MethodHandles.publicLookup().findVirtual(
                    tbParamCls, "recreateUniqueness", MethodType.methodType(void.class));
            tbGetUniqMh = MethodHandles.publicLookup().findVirtual(
                    tbParamCls, "getUniqueness", MethodType.methodType(int.class, int.class, int.class, int.class))
                    .asType(MethodType.methodType(int.class, Object.class, int.class, int.class, int.class));
            Class<?> rtreeCls = Class.forName("net.minecraft.world.level.biome.Climate$RTree");
            tbGetTreeMh = MethodHandles.publicLookup().findVirtual(
                    tbParamCls, "getTree", MethodType.methodType(rtreeCls, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class));
            Class<?> tbSourceCls = Class.forName("terrablender.worldgen.IExtendedMultiNoiseBiomeSource");
            tbSourceCloneMh = MethodHandles.publicLookup().findVirtual(
                    tbSourceCls, "clone", MethodType.methodType(MultiNoiseBiomeSource.class));
            Class<?> tbAccessCls = Class.forName("terrablender.mixin.MultiNoiseBiomeSourceAccess");
            tbSetParamsMh = MethodHandles.publicLookup().findVirtual(
                    tbAccessCls, "setParameters", MethodType.methodType(void.class, Either.class));
            Class<?> tbRegionCls = Class.forName("terrablender.api.Region");
            @SuppressWarnings("unchecked")
            ResourceKey<Biome> dk = (ResourceKey<Biome>) tbRegionCls.getField("DEFERRED_PLACEHOLDER").get(null);
            tbDeferredKey = dk;
        } catch (Throwable ignored) {
        }
        TB_EXTENDED_PARAM_LIST = tbParamCls;
        TB_IS_INITIALIZED = tbInitMh;
        TB_PARAM_CLONE = tbParamCloneMh;
        TB_RECREATE_UNIQUENESS = tbRecreateMh;
        TB_GET_UNIQUENESS = tbGetUniqMh;
        TB_GET_TREE = tbGetTreeMh;
        TB_SOURCE_CLONE = tbSourceCloneMh;
        TB_SET_PARAMETERS = tbSetParamsMh;
        TB_DEFERRED_KEY = tbDeferredKey;

        Class<?> tbEndCls = null;
        Field tbEndInitField = null;
        try {
            tbEndCls = Class.forName("terrablender.worldgen.IExtendedTheEndBiomeSource");
            Field f = TheEndBiomeSource.class.getDeclaredField("tbInitialized");
            f.setAccessible(true);
            tbEndInitField = f;
        } catch (Throwable ignored) {
        }
        TB_EXTENDED_END_SOURCE = tbEndCls;
        TB_END_INITIALIZED_FIELD = tbEndInitField;

        Set<?> fapiEndSet = null;
        try {
            Class<?> fapiEndCls = Class.forName("net.fabricmc.fabric.impl.biome.TheEndBiomeData");
            Field f = fapiEndCls.getField("ADDED_BIOMES");
            Object val = f.get(null);
            if (val instanceof Set<?> s) {
                fapiEndSet = s;
            }
        } catch (Throwable ignored) {
        }
        FAPI_END_ADDED_BIOMES = fapiEndSet;

        Class<?> moogBeardCls = null;
        MethodHandle moogPiecesMh = null;
        MethodHandle moogJuncMh = null;
        try {
            moogBeardCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.beardifier.EnhancedBeardifierData");
            Class<?> objListCls = Class.forName("it.unimi.dsi.fastutil.objects.ObjectList");
            moogPiecesMh = MethodHandles.publicLookup().findVirtual(
                    moogBeardCls, "moogs_structures_getEnhancedPieces", MethodType.methodType(objListCls));
            moogJuncMh = MethodHandles.publicLookup().findVirtual(
                    moogBeardCls, "moogs_structures_getEnhancedJunctions", MethodType.methodType(objListCls));
        } catch (Throwable ignored) {
        }
        MOOG_ENHANCED_BEARDIFIER = moogBeardCls;
        MOOG_GET_PIECES = moogPiecesMh;
        MOOG_GET_JUNCTIONS = moogJuncMh;

        Class<?> beCheckerCls = null;
        MethodHandle beTargetMh = null;
        try {
            beCheckerCls = Class.forName("org.betterx.betterend.interfaces.BETargetChecker");
            beTargetMh = MethodHandles.publicLookup().findVirtual(
                    beCheckerCls, "be_isTarget", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
        } catch (Throwable ignored) {
        }
        BE_TARGET_CHECKER = beCheckerCls;
        BE_IS_TARGET = beTargetMh;
    }

    private ModCompat() {}

    public static boolean isVanillaClass(Class<?> cls) {
        return cls != null && VANILLA_CLASS.get(cls);
    }

    public static boolean isVanillaDensityFunctionsInner(Class<?> cls) {
        return cls != null && VANILLA_DF_INNER.get(cls);
    }

    public static boolean isCustomNoiseChunkTarget(Object noiseChunk) {
        if (noiseChunk == null || BE_TARGET_CHECKER == null || BE_IS_TARGET == null || !BE_TARGET_CHECKER.isInstance(noiseChunk)) {
            return false;
        }
        try {
            return (boolean) BE_IS_TARGET.invokeExact(noiseChunk);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isVanillaMultiNoiseSource(BiomeSource source) {
        return source != null && source.getClass() == MultiNoiseBiomeSource.class;
    }

    public static boolean isVanillaMultiNoiseParams(Climate.ParameterList<?> params) {
        if (params == null || params.getClass() != Climate.ParameterList.class) {
            return false;
        }
        if (TB_EXTENDED_PARAM_LIST != null && TB_EXTENDED_PARAM_LIST.isInstance(params)) {
            if (TB_IS_INITIALIZED == null) {
                return false;
            }
            try {
                if ((boolean) TB_IS_INITIALIZED.invokeExact((Object) params)) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    public static boolean isVanillaUnmodifiedEndBiomeSource(TheEndBiomeSource source) {
        if (source == null || source.getClass() != TheEndBiomeSource.class) {
            return false;
        }
        if (FAPI_END_ADDED_BIOMES != null && !FAPI_END_ADDED_BIOMES.isEmpty()) {
            return false;
        }
        if (FAPI_END_ADDED_BIOMES == null && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-biome-api-v1")) {
            return false;
        }
        if (TB_EXTENDED_END_SOURCE != null && TB_EXTENDED_END_SOURCE.isInstance(source)) {
            if (TB_END_INITIALIZED_FIELD == null) {
                return false;
            }
            try {
                if (TB_END_INITIALIZED_FIELD.getBoolean(source)) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasMoogEnhancedBeardifier(Object beardifier) {
        if (beardifier == null || MOOG_ENHANCED_BEARDIFIER == null || !MOOG_ENHANCED_BEARDIFIER.isInstance(beardifier)) {
            return false;
        }
        try {
            if (MOOG_GET_PIECES != null && MOOG_GET_PIECES.invoke(beardifier) instanceof Collection<?> c && !c.isEmpty()) {
                return true;
            }
            if (MOOG_GET_JUNCTIONS != null && MOOG_GET_JUNCTIONS.invoke(beardifier) instanceof Collection<?> c && !c.isEmpty()) {
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isEffectivelyEmptyBeardifier(Object beardifier) {
        if (beardifier == null || beardifier == Beardifier.EMPTY) {
            return true;
        }
        if (isVanillaClass(beardifier.getClass()) && "BeardifierMarker".equals(beardifier.getClass().getSimpleName())) {
            return true;
        }
        if (beardifier instanceof BeardifierAccess ba && ba.chunking$getAffectedBox() == null) {
            return !hasMoogEnhancedBeardifier(beardifier);
        }
        return false;
    }

    /**
     * Returns a per-thread TerraBlender {@link MultiNoiseBiomeSource} clone so worker threads
     * never contend on {@code Area}'s internal {@code StampedLock} or allocate a clone per chunk.
     */
    public static BiomeSource threadLocalBiomeSource(BiomeSource original) {
        if (TB_EXTENDED_PARAM_LIST == null
                || TB_SOURCE_CLONE == null
                || TB_PARAM_CLONE == null
                || TB_RECREATE_UNIQUENESS == null
                || TB_SET_PARAMETERS == null
                || !isVanillaMultiNoiseSource(original)
                || !(original instanceof MultiNoiseBiomeSourceAccess mnAcc)) {
            return original;
        }
        Climate.ParameterList<?> params = mnAcc.chunking$parameters();
        if (params == null || !TB_EXTENDED_PARAM_LIST.isInstance(params) || isVanillaMultiNoiseParams(params)) {
            return original;
        }
        IdentityHashMap<BiomeSource, BiomeSource> map = TB_TL_SOURCE_CACHE.get().currentMap();
        BiomeSource cached = map.get(original);
        if (cached != null) {
            return cached;
        }
        try {
            MultiNoiseBiomeSource clonedSource = (MultiNoiseBiomeSource) TB_SOURCE_CLONE.invoke(original);
            Climate.ParameterList<?> clonedParams = (Climate.ParameterList<?>) TB_PARAM_CLONE.invoke(params);
            TB_RECREATE_UNIQUENESS.invoke(clonedParams);
            TB_SET_PARAMETERS.invoke(clonedSource, Either.left(clonedParams));
            if (map.size() >= 8) {
                map.clear();
            }
            map.put(original, clonedSource);
            return clonedSource;
        } catch (Throwable ignored) {
            return original;
        }
    }

    public static boolean isStatelessModDensityFunction(DensityFunction fn) {
        return fn != null && STATELESS_MOD_DF.get(fn.getClass());
    }

    public static boolean isTerraBlenderInitializedParams(Climate.ParameterList<?> params) {
        if (params == null
                || TB_EXTENDED_PARAM_LIST == null
                || TB_IS_INITIALIZED == null
                || TB_GET_UNIQUENESS == null
                || TB_GET_TREE == null
                || !TB_EXTENDED_PARAM_LIST.isInstance(params)) {
            return false;
        }
        try {
            return (boolean) TB_IS_INITIALIZED.invokeExact((Object) params);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Climate.ParameterList<?> threadLocalTerraBlenderParams(Climate.ParameterList<?> params) {
        if (params == null || TB_PARAM_CLONE == null || TB_RECREATE_UNIQUENESS == null
                || !isTerraBlenderInitializedParams(params)) {
            return params;
        }
        IdentityHashMap<Climate.ParameterList<?>, Climate.ParameterList<?>> map = TB_TL_PARAMS_CACHE.get().currentMap();
        Climate.ParameterList<?> cached = map.get(params);
        if (cached != null) {
            return cached;
        }
        try {
            Climate.ParameterList<?> cloned = (Climate.ParameterList<?>) TB_PARAM_CLONE.invoke(params);
            TB_RECREATE_UNIQUENESS.invoke(cloned);
            if (map.size() >= 8) {
                map.clear();
            }
            map.put(params, cloned);
            return cloned;
        } catch (Throwable ignored) {
            return params;
        }
    }

    public static int getTerraBlenderUniqueness(Climate.ParameterList<?> params, int qx, int qy, int qz) {
        if (TB_GET_UNIQUENESS == null || params == null) {
            return 0;
        }
        try {
            return (int) TB_GET_UNIQUENESS.invokeExact((Object) params, qx, qy, qz);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    public static ClimateRTreeAccess<Holder<Biome>> getTerraBlenderTree(Climate.ParameterList<?> params, int uniqueness) {
        if (TB_GET_TREE == null || params == null) {
            return null;
        }
        try {
            Object tree = TB_GET_TREE.invokeExact((Object) params, uniqueness);
            return (tree instanceof ClimateRTreeAccess<?> acc)
                    ? (ClimateRTreeAccess<Holder<Biome>>) acc
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isTerraBlenderDeferred(Holder<Biome> holder) {
        return holder != null && TB_DEFERRED_KEY != null && holder.is(TB_DEFERRED_KEY);
    }

    /**
     * Zero-allocation raw TerraBlender biome lookup using thread-local {@code ParameterList}
     * (no {@code Area} lock contention) and flat iterative RTree search without {@code TargetPoint}.
     */
    @SuppressWarnings("unchecked")
    public static Holder<Biome> searchTerraBlenderRaw(
            Climate.ParameterList<?> tlParams,
            int qx,
            int qy,
            int qz,
            long t,
            long h,
            long c,
            long e,
            long d,
            long w
    ) {
        if (tlParams == null) {
            return null;
        }
        int uniqueness = getTerraBlenderUniqueness(tlParams, qx, qy, qz);
        ClimateRTreeAccess<Holder<Biome>> tree = getTerraBlenderTree(tlParams, uniqueness);
        if (tree != null) {
            Holder<Biome> found = tree.chunking$searchRaw(t, h, c, e, d, w);
            if (found != null && !isTerraBlenderDeferred(found)) {
                return found;
            }
        }
        ClimateRTreeAccess<Holder<Biome>> vanillaTree = getTerraBlenderTree(tlParams, 0);
        if (vanillaTree != null) {
            return vanillaTree.chunking$searchRaw(t, h, c, e, d, w);
        }
        if (tlParams instanceof ClimateParameterListAccess<?> pAcc) {
            Object idx = pAcc.chunking$index();
            if (idx instanceof ClimateRTreeAccess<?> rtree) {
                return ((ClimateRTreeAccess<Holder<Biome>>) rtree).chunking$searchRaw(t, h, c, e, d, w);
            }
        }
        return null;
    }
}
