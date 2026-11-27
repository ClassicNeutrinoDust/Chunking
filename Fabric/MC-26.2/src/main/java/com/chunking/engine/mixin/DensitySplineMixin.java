package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import net.minecraft.util.BoundedFloatFunction;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Eliminates {@code new DensityFunctions$Spline$Point(ctx)} and binary-search lambda allocations
 * whenever {@link DensityFunctions.Spline#compute} is evaluated outside a JIT kernel (e.g. modded trees).
 */
@Mixin(DensityFunctions.Spline.class)
public abstract class DensitySplineMixin {
    @Shadow
    @Final
    private CubicSpline<DensityFunctions.Spline.Coordinate> spline;

    @Shadow
    @Final
    private BoundedFloatFunction<DensityFunctions.Spline.Point> sampler;

    /**
     * @author Chunking
     * @reason Zero-allocation recursive spline evaluation without Spline$Point or IntPredicate lambda.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext ctx) {
        if (!ModConfig.SIMD_NOISE || !(this.spline instanceof CubicSpline.Constant<?>) && !(this.spline instanceof CubicSpline.Multipoint<?>)) {
            return this.sampler.apply(new DensityFunctions.Spline.Point(ctx));
        }
        return chunking$evalSpline(this.spline, ctx);
    }

    @Unique
    private static float chunking$evalSpline(
            CubicSpline<DensityFunctions.Spline.Coordinate> spline,
            DensityFunction.FunctionContext ctx
    ) {
        if (spline instanceof CubicSpline.Constant<?> c) {
            return c.value();
        }
        @SuppressWarnings("unchecked")
        CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> mp =
                (CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>) spline;
        float coord = (float) mp.coordinate().function().compute(ctx);
        float[] locs = mp.locations();
        float[] derivs = mp.derivatives();
        List<CubicSpline<DensityFunctions.Spline.Coordinate>> vals = mp.values();
        int i = chunking$findInterval(locs, coord);
        int last = locs.length - 1;
        if (i < 0) {
            return chunking$linearExtend(coord, locs, chunking$evalSpline(vals.get(0), ctx), derivs, 0);
        }
        if (i == last) {
            return chunking$linearExtend(coord, locs, chunking$evalSpline(vals.get(last), ctx), derivs, last);
        }
        float l0 = locs[i];
        float l1 = locs[i + 1];
        float dx = l1 - l0;
        float t = (coord - l0) / dx;
        float v0 = chunking$evalSpline(vals.get(i), ctx);
        float v1 = chunking$evalSpline(vals.get(i + 1), ctx);
        float diff = v1 - v0;
        float a = derivs[i] * dx - diff;
        float b = -derivs[i + 1] * dx + diff;
        return Mth.lerp(t, v0, v1) + t * (1.0F - t) * Mth.lerp(t, a, b);
    }

    @Unique
    private static int chunking$findInterval(float[] locs, float c) {
        int lo = 0;
        int hi = locs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c < locs[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo - 1;
    }

    @Unique
    private static float chunking$linearExtend(float c, float[] locs, float val, float[] derivs, int idx) {
        float d = derivs[idx];
        return d == 0.0F ? val : val + d * (c - locs[idx]);
    }
}
