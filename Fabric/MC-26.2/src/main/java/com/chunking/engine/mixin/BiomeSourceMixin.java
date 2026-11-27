package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.ClimateSamplerAccess;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Accelerates {@link BiomeSource#findClosestBiome3d} (used by {@code /locate biome} and Nature's Compass):
 * <ul>
 *   <li>Eliminates {@code Set.stream().collect()} and {@code AbstractIterator}/{@code MutableBlockPos}
 *       allocations across 160,000+ spiral columns.</li>
 *   <li>Skips redundant vertical Y samples when {@link Climate.Sampler} has constant depth across Y.</li>
 * </ul>
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin {
    @Shadow
    public abstract Set<Holder<Biome>> possibleBiomes();

    @Shadow
    public abstract Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler);

    @Inject(
            method = "findClosestBiome3d",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$fastFindClosestBiome3d(
            BlockPos origin,
            int radius,
            int horizontalStep,
            int verticalStep,
            Predicate<Holder<Biome>> biomePredicate,
            Climate.Sampler sampler,
            LevelReader level,
            CallbackInfoReturnable<Pair<BlockPos, Holder<Biome>>> cir
    ) {
        if (!ModConfig.SIMD_NOISE || horizontalStep <= 0 || verticalStep <= 0) {
            return;
        }
        Set<Holder<Biome>> allPossible = this.possibleBiomes();
        Holder<Biome> singleTarget = null;
        ObjectOpenHashSet<Holder<Biome>> multiTargets = null;
        for (Holder<Biome> b : allPossible) {
            if (biomePredicate.test(b)) {
                if (singleTarget == null && multiTargets == null) {
                    singleTarget = b;
                } else {
                    if (multiTargets == null) {
                        multiTargets = new ObjectOpenHashSet<>(8);
                        multiTargets.add(singleTarget);
                        singleTarget = null;
                    }
                    multiTargets.add(b);
                }
            }
        }
        if (singleTarget == null && multiTargets == null) {
            cir.setReturnValue(null);
            return;
        }

        int[] yLevels = Mth.outFromOrigin(
                origin.getY(),
                level.getMinY() + 1,
                level.getMaxY() + 1,
                verticalStep
        ).toArray();
        if (yLevels.length == 0) {
            cir.setReturnValue(null);
            return;
        }

        int[] qyLevels = new int[yLevels.length];
        for (int i = 0; i < yLevels.length; i++) {
            qyLevels[i] = QuartPos.fromBlock(yLevels[i]);
        }

        boolean constY = ((Object) sampler instanceof ClimateSamplerAccess sAcc)
                && sAcc.chunking$isDepthConstY()
                && ((BiomeSource) (Object) this).getClass() == net.minecraft.world.level.biome.MultiNoiseBiomeSource.class;
        int yCount = constY ? 1 : yLevels.length;

        if (radius < 0) {
            cir.setReturnValue(null);
            return;
        }
        int originX = origin.getX();
        int originZ = origin.getZ();
        int maxSteps = Math.floorDiv(radius, horizontalStep);
        long side = (long) maxSteps * 2L + 1L;
        long total = side * side;

        int dx = 0;
        int dz = 0;
        int dirX = 1; // EAST
        int dirZ = 0;
        int legSize = 1;
        int legPos = 0;
        int leg = 0;

        for (long step = 0; step < total; step++) {
            int bx = originX + dx * horizontalStep;
            int bz = originZ + dz * horizontalStep;
            int qx = QuartPos.fromBlock(bx);
            int qz = QuartPos.fromBlock(bz);
            for (int yi = 0; yi < yCount; yi++) {
                Holder<Biome> found = this.getNoiseBiome(qx, qyLevels[yi], qz, sampler);
                boolean match = singleTarget != null ? (found == singleTarget || singleTarget.equals(found)) : multiTargets.contains(found);
                if (match) {
                    cir.setReturnValue(Pair.of(new BlockPos(bx, yLevels[yi], bz), found));
                    return;
                }
            }
            dx += dirX;
            dz += dirZ;
            if (++legPos >= legSize) {
                legPos = 0;
                leg++;
                if ((leg & 1) == 0) {
                    legSize++;
                }
                int tmp = dirX;
                dirX = -dirZ;
                dirZ = tmp;
            }
        }
        cir.setReturnValue(null);
    }
}
