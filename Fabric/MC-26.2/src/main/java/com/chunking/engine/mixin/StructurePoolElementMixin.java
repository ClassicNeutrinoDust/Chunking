package com.chunking.engine.mixin;

import com.chunking.engine.io.SyncCodec;
import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StructurePoolElement.class)
public abstract class StructurePoolElementMixin {
    @Shadow
    @Final
    @Mutable
    public static Codec<StructurePoolElement> CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void chunking$wrapSynchronizedCodec(CallbackInfo ci) {
        CODEC = new SyncCodec<>(CODEC);
    }
}
