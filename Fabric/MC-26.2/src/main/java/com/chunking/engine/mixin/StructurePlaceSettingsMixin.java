package com.chunking.engine.mixin;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(StructurePlaceSettings.class)
public abstract class StructurePlaceSettingsMixin {
    @Shadow
    @Final
    @Mutable
    private List<StructureProcessor> processors;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$syncProcessors(CallbackInfo ci) {
        if (this.processors != null) {
            this.processors = Collections.synchronizedList(this.processors);
        }
    }
}
