package com.chunking.engine.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(MineshaftPieces.MineShaftRoom.class)
public abstract class MineshaftRoomMixin {
    @Shadow
    @Final
    @Mutable
    private List<BoundingBox> childEntranceBoxes;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void chunking$syncChildEntranceBoxes(CallbackInfo ci) {
        if (this.childEntranceBoxes != null) {
            this.childEntranceBoxes = Collections.synchronizedList(this.childEntranceBoxes);
        }
    }
}
