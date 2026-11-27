package com.chunking.engine.mixin;

import com.chunking.engine.access.NoiseSliceEval;
import com.chunking.engine.access.SliceProviderAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements {@link SliceProviderAccess} on {@code NoiseChunk$1} ({@code sliceFillingContextProvider})
 * so 2D vertical-slice caches can read the invariant {@code (blockX, blockZ)} context without
 * mutating {@code interpolationCounter}, and delegates {@code fillAllDirectly} to the compiled JIT kernel.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$1")
public abstract class NoiseSliceProviderMixin implements SliceProviderAccess {
    @Shadow
    @Final
    NoiseChunk this$0;

    @Override
    public NoiseChunk chunking$getNoiseChunk() {
        return this.this$0;
    }

    @Inject(method = "fillAllDirectly", at = @At("HEAD"), cancellable = true)
    private void chunking$jitFillVerticalSlice(double[] out, DensityFunction function, CallbackInfo ci) {
        if (((NoiseSliceEval) this.this$0).chunking$tryFillVerticalSlice(out, function)) {
            ci.cancel();
        }
    }
}
