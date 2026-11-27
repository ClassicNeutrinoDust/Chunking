package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.ClimateParameterListAccess;
import com.chunking.engine.access.ClimateRTreeAccess;
import com.chunking.engine.access.ClimateSamplerAccess;
import com.chunking.engine.world.ModCompat;
import com.chunking.engine.access.MultiNoiseBiomeSourceAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Function;

@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin implements MultiNoiseBiomeSourceAccess {
    @Shadow
    @Final
    private Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters;

    @Unique
    private record CachedParams(
            Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> either,
            Climate.ParameterList<Holder<Biome>> params
    ) {}

    @Unique
    private volatile CachedParams chunking$cachedParams;

    @Override
    public Climate.ParameterList<Holder<Biome>> chunking$parameters() {
        Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> p = this.parameters;
        CachedParams cached = this.chunking$cachedParams;
        if (cached != null && cached.either == p) {
            return cached.params;
        }
        Climate.ParameterList<Holder<Biome>> resolved = p.map(Function.identity(), holder -> holder.value().parameters());
        this.chunking$cachedParams = new CachedParams(p, resolved);
        return resolved;
    }

    @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
    @SuppressWarnings("unchecked")
    private Holder<Biome> chunking$fastGetNoiseBiome(
            int qx,
            int qy,
            int qz,
            Climate.Sampler sampler,
            Operation<Holder<Biome>> original
    ) {
        if (ModConfig.SIMD_NOISE
                && ModCompat.isVanillaMultiNoiseSource((BiomeSource) (Object) this)
                && (Object) sampler instanceof ClimateSamplerAccess sAcc) {
            Climate.ParameterList<Holder<Biome>> params = this.chunking$parameters();
            if (ModCompat.isVanillaMultiNoiseParams(params)
                    && (Object) params instanceof ClimateParameterListAccess<?> plAcc) {
                ClimateRTreeAccess<Holder<Biome>> rtree =
                        ((ClimateParameterListAccess<Holder<Biome>>) plAcc).chunking$index();
                if (rtree != null) {
                    Holder<Biome> res = sAcc.chunking$sampleAndSearchRaw(qx, qy, qz, rtree);
                    if (res != null) {
                        return res;
                    }
                }
            } else if (ModCompat.isTerraBlenderInitializedParams(params)) {
                Climate.ParameterList<?> tlParams = ModCompat.threadLocalTerraBlenderParams(params);
                Holder<Biome> res = sAcc.chunking$sampleAndSearchTerraBlenderRaw(qx, qy, qz, tlParams);
                if (res != null) {
                    return res;
                }
            }
        }
        return original.call(qx, qy, qz, sampler);
    }
}
