package com.chunking.engine.mixin;

import com.mojang.math.Transformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eagerly decomposes Transformation matrices on construction so structure threads never race on lazy SVD.
 */
@Mixin(Transformation.class)
public abstract class TransformationMixin {
    @Shadow
    private void ensureDecomposed() {
        throw new AssertionError();
    }

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void chunking$eagerDecompose(CallbackInfo ci) {
        this.ensureDecomposed();
    }
}
