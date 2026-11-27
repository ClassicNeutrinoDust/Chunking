package com.chunking.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Pure-register pre-filter for {@link RandomSpreadStructurePlacement#isPlacementChunk} eliminating
 * per-set {@code WorldgenRandom}/{@code LegacyRandomSource}/{@code AtomicLong}/{@code ChunkPos}
 * allocations when evaluating hundreds of modded {@code StructureSet}s per chunk.
 */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class StructurePlacementMixin extends StructurePlacement {
    @Unique
    private static final long CHUNKING$LCG_MULT = 25214903917L;
    @Unique
    private static final long CHUNKING$LCG_MASK = (1L << 48) - 1L;

    @Shadow
    @Final
    private int spacing;

    @Shadow
    @Final
    private int separation;

    @Shadow
    @Final
    private RandomSpreadType spreadType;

    protected StructurePlacementMixin() {
        super(null, null, 0.0F, 0, null);
    }

    @WrapMethod(method = "isPlacementChunk(Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;II)Z")
    private boolean chunking$fastRandomSpreadCheck(
            ChunkGeneratorStructureState state,
            int chunkX,
            int chunkZ,
            Operation<Boolean> original
    ) {
        if (((Object) this).getClass() != RandomSpreadStructurePlacement.class) {
            return original.call(state, chunkX, chunkZ);
        }
        int sp = this.spacing;
        int bound = sp - this.separation;
        if (sp <= 0 || bound <= 0) {
            return original.call(state, chunkX, chunkZ);
        }

        int regX = Math.floorDiv(chunkX, sp);
        int localX = chunkX - regX * sp;
        if (localX >= bound) {
            return false;
        }
        int regZ = Math.floorDiv(chunkZ, sp);
        int localZ = chunkZ - regZ * sp;
        if (localZ >= bound) {
            return false;
        }

        long rawSeed = (long) regX * 341873128712L
                + (long) regZ * 132897987541L
                + state.getLevelSeed()
                + (long) this.salt();
        long s = (rawSeed ^ CHUNKING$LCG_MULT) & CHUNKING$LCG_MASK;
        boolean pow2 = (bound & (bound - 1)) == 0;

        RandomSpreadType type = this.spreadType;
        if (type == RandomSpreadType.LINEAR) {
            int ox;
            if (pow2) {
                s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                ox = (int) (((long) bound * (long) (int) (s >> 17)) >> 31);
            } else {
                int bits;
                do {
                    s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                    bits = (int) (s >> 17);
                    ox = bits % bound;
                } while (bits - ox + (bound - 1) < 0);
            }
            if (ox != localX) {
                return false;
            }
            int oz;
            if (pow2) {
                s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                oz = (int) (((long) bound * (long) (int) (s >> 17)) >> 31);
            } else {
                int bits;
                do {
                    s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                    bits = (int) (s >> 17);
                    oz = bits % bound;
                } while (bits - oz + (bound - 1) < 0);
            }
            return oz == localZ;
        }

        if (type == RandomSpreadType.TRIANGULAR) {
            int x0, x1;
            if (pow2) {
                s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                x0 = (int) (((long) bound * (long) (int) (s >> 17)) >> 31);
                s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                x1 = (int) (((long) bound * (long) (int) (s >> 17)) >> 31);
            } else {
                int bits;
                do {
                    s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                    bits = (int) (s >> 17);
                    x0 = bits % bound;
                } while (bits - x0 + (bound - 1) < 0);
                do {
                    s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                    bits = (int) (s >> 17);
                    x1 = bits % bound;
                } while (bits - x1 + (bound - 1) < 0);
            }
            if (((x0 + x1) / 2) != localX) {
                return false;
            }
            int z0, z1;
            if (pow2) {
                s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                z0 = (int) (((long) bound * (long) (int) (s >> 17)) >> 31);
                s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                z1 = (int) (((long) bound * (long) (int) (s >> 17)) >> 31);
            } else {
                int bits;
                do {
                    s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                    bits = (int) (s >> 17);
                    z0 = bits % bound;
                } while (bits - z0 + (bound - 1) < 0);
                do {
                    s = (s * CHUNKING$LCG_MULT + 11L) & CHUNKING$LCG_MASK;
                    bits = (int) (s >> 17);
                    z1 = bits % bound;
                } while (bits - z1 + (bound - 1) < 0);
            }
            return ((z0 + z1) / 2) == localZ;
        }

        return original.call(state, chunkX, chunkZ);
    }
}
