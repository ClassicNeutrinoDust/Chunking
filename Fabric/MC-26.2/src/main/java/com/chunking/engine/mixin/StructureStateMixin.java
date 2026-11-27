package com.chunking.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Serializes lazy one-time structure ring placement initialization in {@link ChunkGeneratorStructureState}
 * so parallel {@code generateStructureStarts} workers never race on {@code hasGeneratedPositions}.
 */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class StructureStateMixin {
    @Shadow
    private boolean hasGeneratedPositions;

    @Unique
    private volatile boolean chunking$positionsGenerated;

    @WrapMethod(method = "ensureStructuresGenerated")
    private void chunking$syncEnsureStructuresGenerated(Operation<Void> original) {
        if (this.chunking$positionsGenerated) {
            return;
        }
        synchronized (this) {
            if (!this.chunking$positionsGenerated) {
                if (!this.hasGeneratedPositions) {
                    original.call();
                }
                this.chunking$positionsGenerated = true;
            }
        }
    }
}
