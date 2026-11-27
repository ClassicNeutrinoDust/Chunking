package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.world.RouterCache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Memoizes {@code DensityFunction$1RecursiveVisitor#apply} by input reference identity
 * during {@code NoiseChunk} initialization without {@code CallbackInfoReturnable} allocations.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunction$1RecursiveVisitor")
public abstract class DensityRecursiveVisitorMixin {
    @WrapMethod(method = "apply(Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/DensityFunction;")
    private DensityFunction chunking$memoApply(DensityFunction df, Operation<DensityFunction> original) {
        if (!ModConfig.SIMD_NOISE) {
            return original.call(df);
        }
        RouterCache.WrapState s = RouterCache.getIfActive();
        if (s == null) {
            return original.call(df);
        }
        DensityFunction cached = s.memo.get(df);
        if (cached != null) {
            return cached;
        }
        DensityFunction res = original.call(df);
        s.memo.put(df, res);
        return res;
    }
}
