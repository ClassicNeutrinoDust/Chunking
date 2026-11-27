package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.SurfaceContextAccessor;
import com.chunking.engine.world.FastSurface;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Captures and normalizes constant-false and constant-true {@code SurfaceRules.Condition}
 * singletons emitted by {@code BiomeConditionSource.apply(Context)} when a chunk's biome set
 * cannot match or always matches.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource")
public abstract class SurfaceBiomeConditionMixin {
    @Shadow
    private boolean canNeverMatch(Set<Holder<Biome>> biomes) {
        throw new AssertionError();
    }

    @Shadow
    private boolean willAlwaysMatch(Set<Holder<Biome>> biomes) {
        throw new AssertionError();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$Condition;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$fastConstantBiomeConditionHead(@Coerce Object context, CallbackInfoReturnable cir) {
        if (!ModConfig.FAST_SURFACE_RULES || context == null) {
            return;
        }
        Object af = FastSurface.alwaysFalse();
        Object at = FastSurface.alwaysTrue();
        if (af == null || at == null) {
            return;
        }
        Set<Holder<Biome>> possible = ((SurfaceContextAccessor) context).chunking$getPossibleBiomes();
        if (possible == null) {
            return;
        }
        if (this.canNeverMatch(possible)) {
            cir.setReturnValue(af);
        } else if (this.willAlwaysMatch(possible)) {
            cir.setReturnValue(at);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$Condition;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void chunking$normalizeConstantBiomeCondition(@Coerce Object context, CallbackInfoReturnable cir) {
        if (!ModConfig.FAST_SURFACE_RULES || context == null) {
            return;
        }
        if (FastSurface.alwaysFalse() != null && FastSurface.alwaysTrue() != null) {
            return;
        }
        Set<Holder<Biome>> possible = ((SurfaceContextAccessor) context).chunking$getPossibleBiomes();
        if (possible == null) {
            return;
        }
        Object ret = cir.getReturnValue();
        if (this.canNeverMatch(possible)) {
            Object reg = FastSurface.registerAlwaysFalse(ret);
            if (reg != ret) {
                cir.setReturnValue(reg);
            }
        } else if (this.willAlwaysMatch(possible)) {
            Object reg = FastSurface.registerAlwaysTrue(ret);
            if (reg != ret) {
                cir.setReturnValue(reg);
            }
        }
    }
}
