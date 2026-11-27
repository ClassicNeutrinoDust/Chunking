package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.simd.JitSupport;
import com.chunking.engine.access.NoiseInterpolatorAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Classifies pure ore-vein {@code NoiseInterpolator} instances at {@link NoiseChunk} construction time
 * so slice filling can skip vertical coordinates above the ore vein ceiling ({@code y <= 64})
 * and replaces {@code ArrayList$Itr} loops in {@code updateForY/X/Z} with indexed traversal.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkOreInitMixin {
    @Shadow
    @Final
    private List<NoiseChunk.NoiseInterpolator> interpolators;

    @Shadow
    @Final
    private Aquifer aquifer;

    @Shadow
    @Final
    private DensityFunction preliminarySurfaceLevel;

    @Shadow
    @Final
    private DensityFunction fullNoiseDensity;

    @Shadow
    private int cellStartBlockX;

    @Shadow
    private int cellStartBlockY;

    @Shadow
    private int cellStartBlockZ;

    @Shadow
    private int inCellX;

    @Shadow
    private int inCellY;

    @Shadow
    private int inCellZ;

    @Shadow
    private long interpolationCounter;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$markOreVeinInterpolators(
            int cellCountXZ,
            RandomState randomState,
            int firstBlockX,
            int firstBlockZ,
            NoiseSettings noiseSettings,
            DensityFunctions.BeardifierOrMarker beardifier,
            NoiseGeneratorSettings settings,
            Aquifer.FluidPicker fluidPicker,
            Blender blender,
            CallbackInfo ci
    ) {
        if (ModConfig.SIMD_NOISE) {
            JitSupport.classifyAndMarkOreInterpolators(
                    randomState,
                    this.interpolators,
                    this.aquifer,
                    this.preliminarySurfaceLevel,
                    this.fullNoiseDensity
            );
        }
    }

    /**
     * @author Chunking
     * @reason Indexed traversal avoiding ArrayList Iterator allocations when SIMD_NOISE is disabled.
     */
    @Overwrite
    public void updateForY(int by, double ty) {
        this.inCellY = by - this.cellStartBlockY;
        for (int i = 0, n = this.interpolators.size(); i < n; i++) {
            ((NoiseInterpolatorAccess) this.interpolators.get(i)).chunking$updateForY(ty);
        }
    }

    /**
     * @author Chunking
     * @reason Indexed traversal avoiding ArrayList Iterator allocations when SIMD_NOISE is disabled.
     */
    @Overwrite
    public void updateForX(int bx, double tx) {
        this.inCellX = bx - this.cellStartBlockX;
        for (int i = 0, n = this.interpolators.size(); i < n; i++) {
            ((NoiseInterpolatorAccess) this.interpolators.get(i)).chunking$updateForX(tx);
        }
    }

    /**
     * @author Chunking
     * @reason Indexed traversal avoiding ArrayList Iterator allocations when SIMD_NOISE is disabled.
     */
    @Overwrite
    public void updateForZ(int bz, double tz) {
        this.inCellZ = bz - this.cellStartBlockZ;
        this.interpolationCounter++;
        for (int i = 0, n = this.interpolators.size(); i < n; i++) {
            ((NoiseInterpolatorAccess) this.interpolators.get(i)).chunking$updateForZ(tz);
        }
    }
}
