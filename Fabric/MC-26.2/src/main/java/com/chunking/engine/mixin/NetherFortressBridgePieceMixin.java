package com.chunking.engine.mixin;

import com.chunking.engine.access.PieceWeightAccess;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces$NetherBridgePiece")
public abstract class NetherFortressBridgePieceMixin {
    @Redirect(
            method = {"updatePieceWeight", "generatePiece"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/NetherFortressPieces$PieceWeight;placeCount:I",
                    opcode = Opcodes.GETFIELD
            )
    )
    private int chunking$getPlaceCountTL(@Coerce Object weight) {
        return ((PieceWeightAccess) weight).chunking$placeCountTL().get();
    }

    @Redirect(
            method = "generatePiece",
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
}
