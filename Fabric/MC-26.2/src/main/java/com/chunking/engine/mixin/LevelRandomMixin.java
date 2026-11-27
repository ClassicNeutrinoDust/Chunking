package com.chunking.engine.mixin;

import com.chunking.engine.core.SafeRandom;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces {@code Level.random} with {@link SafeRandom} so off-thread
 * worldgen/mod accesses never trigger {@code Accessing LegacyRandomSource from multiple threads}.
 */
@Mixin(Level.class)
public abstract class LevelRandomMixin {
    @Shadow
    @Final
    private Thread thread;

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"
            ),
            require = 0
    )
    private RandomSource chunking$createThreadSafeLevelRandom(Operation<RandomSource> original) {
        return new SafeRandom(RandomSupport.generateUniqueSeed(), () -> this.thread);
    }
}
