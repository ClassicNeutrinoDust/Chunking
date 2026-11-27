package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import net.minecraft.util.BoundedFloatFunction;
import net.minecraft.util.CubicSpline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Replaces {@code CubicSpline$Multipoint#mapCoordinates} Java {@code Stream} pipelines and
 * {@code CallbackInfoReturnable} allocations with zero-stream array mapping.
 */
@Mixin(targets = "net.minecraft.util.CubicSpline$Multipoint")
public abstract class CubicSplineMultipointMixin<I extends BoundedFloatFunction<?>> {
    @Shadow @Final private I coordinate;
    @Shadow @Final private float[] locations;
    @Shadow @Final private List<CubicSpline<I>> values;
    @Shadow @Final private float[] derivatives;
    @Shadow @Final private float minValue;
    @Shadow @Final private float maxValue;

    /**
     * @author Chunking
     * @reason Eliminate per-node Stream pipeline and CallbackInfoReturnable allocations during spline coordinate wrapping.
     */
    @Overwrite
    @SuppressWarnings("unchecked")
    public CubicSpline<I> mapCoordinates(UnaryOperator<I> operator) {
        if (!ModConfig.SIMD_NOISE) {
            return new CubicSpline.Multipoint<>(
                    operator.apply(this.coordinate),
                    this.locations,
                    this.values.stream().map(v -> v.mapCoordinates(operator)).toList(),
                    this.derivatives
            );
        }
        I oldCoord = this.coordinate;
        I newCoord = operator.apply(oldCoord);
        List<CubicSpline<I>> oldVals = this.values;
        int n = oldVals.size();
        CubicSpline<I>[] arr = new CubicSpline[n];
        boolean anyChanged = (newCoord != oldCoord);
        boolean boundsUnchanged = newCoord.minValue() == oldCoord.minValue()
                && newCoord.maxValue() == oldCoord.maxValue();
        for (int i = 0; i < n; i++) {
            CubicSpline<I> ov = oldVals.get(i);
            CubicSpline<I> nv = ov.mapCoordinates(operator);
            if (nv != ov) {
                anyChanged = true;
                if (boundsUnchanged && (nv.minValue() != ov.minValue() || nv.maxValue() != ov.maxValue())) {
                    boundsUnchanged = false;
                }
            }
            arr[i] = nv;
        }
        if (!anyChanged) {
            return (CubicSpline<I>) (Object) this;
        }
        List<CubicSpline<I>> newVals = java.util.Arrays.asList(arr);
        if (boundsUnchanged) {
            return new CubicSpline.Multipoint<>(
                    newCoord, this.locations, newVals, this.derivatives, this.minValue, this.maxValue);
        }
        return new CubicSpline.Multipoint<>(newCoord, this.locations, newVals, this.derivatives);
    }
}
