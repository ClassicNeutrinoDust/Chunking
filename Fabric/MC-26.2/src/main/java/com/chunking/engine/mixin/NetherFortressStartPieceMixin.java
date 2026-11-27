package com.chunking.engine.mixin;

import com.chunking.engine.access.PieceWeightAccess;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(NetherFortressPieces.StartPiece.class)
public abstract class NetherFortressStartPieceMixin {
    @Shadow
    @Final
    @Mutable
    private List<?> availableBridgePieces;

    @Shadow
    @Final
    @Mutable
    private List<?> availableCastlePieces;

    @Redirect(
            method = "<init>(Lnet/minecraft/util/RandomSource;II)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/NetherFortressPieces$PieceWeight;placeCount:I",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void chunking$setPlaceCountTL(@Coerce Object weight, int count) {
        ThreadLocal<Integer> tl = ((PieceWeightAccess) weight).chunking$placeCountTL();
        if (count == 0) {
            tl.remove();
        } else {
            tl.set(count);
        }
    }

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void chunking$syncAvailablePieces(CallbackInfo ci) {
        if (this.availableBridgePieces != null) {
            this.availableBridgePieces = Collections.synchronizedList(this.availableBridgePieces);
        }
        if (this.availableCastlePieces != null) {
            this.availableCastlePieces = Collections.synchronizedList(this.availableCastlePieces);
        }
    }
}
