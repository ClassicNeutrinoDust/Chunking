package com.chunking.engine.mixin;

import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(StrongholdPieces.StartPiece.class)
public abstract class StrongholdStartPieceMixin {
    @Shadow
    @Final
    @Mutable
    public List<StructurePiece> pendingChildren;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void chunking$syncPendingChildren(CallbackInfo ci) {
        if (this.pendingChildren != null) {
            this.pendingChildren = Collections.synchronizedList(this.pendingChildren);
        }
    }
}
