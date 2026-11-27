package com.chunking.engine.mixin;

import com.chunking.engine.access.StrongholdAccess;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StrongholdPieces.StairsDown.class)
public abstract class StrongholdStairsDownMixin {
    @Redirect(
            method = "addChildren",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;imposedPiece:Ljava/lang/Class;",
                    opcode = Opcodes.PUTSTATIC
            )
    )
    private void chunking$setImposedPieceTL(Class<?> cls) {
        if (cls == null) {
            StrongholdAccess.IMPOSED_PIECE_TL.remove();
        } else {
            StrongholdAccess.IMPOSED_PIECE_TL.set(cls);
        }
    }
}
