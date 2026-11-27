package com.chunking.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes StructureStart.references atomic and synchronizes per-piece postProcess across chunk workers.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @Shadow
    private int references;

    // Initialized in <init> (mixin field)
    @Unique
    private volatile AtomicInteger chunking$referencesAtomic;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initAtomicReferences(CallbackInfo ci) {
        this.chunking$referencesAtomic = new AtomicInteger(this.references);
    }

    @Redirect(
            method = {"canBeReferenced", "getReferences", "createTag"},
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/structure/StructureStart;references:I",
                    opcode = Opcodes.GETFIELD
            )
    )
    private int chunking$getAtomicReferences(StructureStart self) {
        return this.chunking$referencesAtomic.get();
    }

    /**
     * @author Chunking
     * @reason Thread-safe atomic increment for StructureStart.references across parallel workers.
     */
    @Overwrite
    public void addReference() {
        this.chunking$referencesAtomic.incrementAndGet();
    }

    @WrapOperation(
            method = "placeInChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/StructurePiece;postProcess(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/BlockPos;)V"
            )
    )
    private void chunking$syncPiecePostProcess(
            StructurePiece piece,
            WorldGenLevel level,
            StructureManager structureManager,
            ChunkGenerator generator,
            RandomSource random,
            BoundingBox box,
            ChunkPos chunkPos,
            BlockPos pos,
            Operation<Void> original
    ) {
        synchronized (piece) {
            original.call(piece, level, structureManager, generator, random, box, chunkPos, pos);
        }
    }
}
