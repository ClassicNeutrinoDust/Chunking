package com.chunking.engine.mixin;

import com.chunking.engine.access.PieceWeightAccess;
import com.chunking.engine.access.StrongholdAccess;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Isolates {@link StrongholdPieces}'s static mutable {@code currentPieces}, {@code totalWeight},
 * {@code imposedPiece}, and {@code PieceWeight.placeCount} per worker thread so concurrent
 * Stronghold {@code STRUCTURE_STARTS} generation never corrupts shared state.
 */
@Mixin(StrongholdPieces.class)
public abstract class StrongholdPiecesMixin {
    @Unique
    private static final ThreadLocal<List<?>> CHUNKING$CURRENT_PIECES =
            ThreadLocal.withInitial(ArrayList::new);

    @Unique
    private static final ThreadLocal<Integer> CHUNKING$TOTAL_WEIGHT =
            ThreadLocal.withInitial(() -> 0);

    @Redirect(
            method = "resetPieces",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;currentPieces:Ljava/util/List;",
                    opcode = Opcodes.PUTSTATIC
            )
    )
    private static void chunking$setCurrentPieces(List<?> list) {
        CHUNKING$CURRENT_PIECES.set(list);
    }

    @Redirect(
            method = {"resetPieces", "updatePieceWeight", "generatePieceFromSmallDoor"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;currentPieces:Ljava/util/List;",
                    opcode = Opcodes.GETSTATIC
            )
    )
    private static List<?> chunking$getCurrentPieces() {
        return CHUNKING$CURRENT_PIECES.get();
    }

    @Redirect(
            method = {"updatePieceWeight", "generatePieceFromSmallDoor"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;totalWeight:I",
                    opcode = Opcodes.GETSTATIC
            )
    )
    private static int chunking$getTotalWeight() {
        return CHUNKING$TOTAL_WEIGHT.get();
    }

    @Redirect(
            method = "updatePieceWeight",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;totalWeight:I",
                    opcode = Opcodes.PUTSTATIC
            )
    )
    private static void chunking$setTotalWeight(int weight) {
        CHUNKING$TOTAL_WEIGHT.set(weight);
    }

    @Redirect(
            method = "generatePieceFromSmallDoor",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;imposedPiece:Ljava/lang/Class;",
                    opcode = Opcodes.GETSTATIC
            )
    )
    private static Class<?> chunking$getImposedPiece() {
        return StrongholdAccess.IMPOSED_PIECE_TL.get();
    }

    @Redirect(
            method = {"resetPieces", "generatePieceFromSmallDoor"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces;imposedPiece:Ljava/lang/Class;",
                    opcode = Opcodes.PUTSTATIC
            )
    )
    private static void chunking$setImposedPiece(Class<?> cls) {
        if (cls == null) {
            StrongholdAccess.IMPOSED_PIECE_TL.remove();
        } else {
            StrongholdAccess.IMPOSED_PIECE_TL.set(cls);
        }
    }

    @Redirect(
            method = {"updatePieceWeight", "generatePieceFromSmallDoor"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces$PieceWeight;placeCount:I",
                    opcode = Opcodes.GETFIELD
            )
    )
    private static int chunking$getPieceWeightPlaceCount(@Coerce Object weight) {
        return ((PieceWeightAccess) weight).chunking$placeCountTL().get();
    }

    @Redirect(
            method = {"resetPieces", "generatePieceFromSmallDoor"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/structures/StrongholdPieces$PieceWeight;placeCount:I",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private static void chunking$setPieceWeightPlaceCount(@Coerce Object weight, int count) {
        ThreadLocal<Integer> tl = ((PieceWeightAccess) weight).chunking$placeCountTL();
        if (count == 0) {
            tl.remove();
        } else {
            tl.set(count);
        }
    }
}
