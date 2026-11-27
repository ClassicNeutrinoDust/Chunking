package com.chunking.engine.mixin;

import com.chunking.engine.access.PieceWeightAccess;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces$PieceWeight")
public abstract class StrongholdPieceWeightMixin implements PieceWeightAccess {
    // Initialized in <init> (mixin field)
    @Unique
    private volatile ThreadLocal<Integer> chunking$placeCount;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void chunking$initPlaceCount(CallbackInfo ci) {
        if (this.chunking$placeCount == null) {
            this.chunking$placeCount = ThreadLocal.withInitial(() -> 0);
        }
    }

    @Override
    public ThreadLocal<Integer> chunking$placeCountTL() {
        return this.chunking$placeCount;
    }

    @Redirect(
            method = {"doPlace", "isValid"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces$PieceWeight;placeCount:I",
                    opcode = Opcodes.GETFIELD
            )
    )
    private int chunking$getLocalPlaceCount(@Coerce Object self) {
        return this.chunking$placeCount.get();
    }
}
