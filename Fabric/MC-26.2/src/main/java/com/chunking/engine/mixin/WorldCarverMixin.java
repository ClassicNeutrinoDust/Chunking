package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Exits {@link WorldCarver#carveEllipsoid} before per-call allocations when an ellipsoid misses
 * the chunk bounds, while leaving intersecting ellipsoids on the vanilla {@code carveBlock} path
 * so mod carver hooks (e.g. StreamsReflowing river corridor guards) always run.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverMixin<C extends CarverConfiguration> {
    @Inject(method = "carveEllipsoid", at = @At("HEAD"), cancellable = true)
    private void chunking$earlyExitOutsideChunk(
            CarvingContext context,
            C config,
            ChunkAccess chunk,
            Function<BlockPos, Holder<Biome>> biomeGetter,
            Aquifer aquifer,
            double x,
            double y,
            double z,
            double horizontalRadius,
            double verticalRadius,
            CarvingMask carvingMask,
            WorldCarver.CarveSkipChecker skipChecker,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ModConfig.SIMD_NOISE) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        double maxDist = 16.0D + horizontalRadius * 2.0D;
        if (Math.abs(x - chunkPos.getMiddleBlockX()) > maxDist
                || Math.abs(z - chunkPos.getMiddleBlockZ()) > maxDist) {
            cir.setReturnValue(false);
            return;
        }
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        int minX = Math.max(Mth.floor(x - horizontalRadius) - minBlockX - 1, 0);
        int maxX = Math.min(Mth.floor(x + horizontalRadius) - minBlockX, 15);
        int minZ = Math.max(Mth.floor(z - horizontalRadius) - minBlockZ - 1, 0);
        int maxZ = Math.min(Mth.floor(z + horizontalRadius) - minBlockZ, 15);
        int minGenY = context.getMinGenY();
        int minY = Math.max(Mth.floor(y - verticalRadius) - 1, minGenY + 1);
        int topPad = chunk.isUpgrading() ? 0 : 7;
        int maxY = Math.min(Mth.floor(y + verticalRadius) + 1, minGenY + context.getGenDepth() - 1 - topPad);
        if (minX > maxX || minZ > maxZ || maxY <= minY) {
            cir.setReturnValue(false);
        }
    }
}
