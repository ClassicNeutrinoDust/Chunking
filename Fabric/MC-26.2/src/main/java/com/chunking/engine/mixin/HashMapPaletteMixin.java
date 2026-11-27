package com.chunking.engine.mixin;

import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.chunk.HashMapPalette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces Guava's {@code Iterators$5} filtering iterator in {@link HashMapPalette#getEntries()} with a
 * direct indexed loop over {@link CrudeIncrementalIntIdentityHashBiMap#byId(int)}.
 */
@Mixin(HashMapPalette.class)
public abstract class HashMapPaletteMixin<T> {
    @Shadow
    @Final
    private CrudeIncrementalIntIdentityHashBiMap<T> values;

    @Inject(method = "getEntries", at = @At("HEAD"), cancellable = true)
    private void chunking$fastGetEntries(CallbackInfoReturnable<List<T>> cir) {
        int size = this.values.size();
        ArrayList<T> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            T val = this.values.byId(i);
            if (val != null) {
                entries.add(val);
            }
        }
        cir.setReturnValue(entries);
    }
}
