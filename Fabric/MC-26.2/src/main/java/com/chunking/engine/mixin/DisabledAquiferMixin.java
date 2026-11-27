package com.chunking.engine.mixin;

import com.chunking.engine.access.AquiferAccess;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes {@link AquiferAccess} on {@link Aquifer#createDisabled(Aquifer.FluidPicker)}
 * ({@code Aquifer$1}) used in The Nether and The End, enabling fast uniform air/void/lava
 * cell skipping and bulk solid/mixed fills across all dimensions.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$1")
public abstract class DisabledAquiferMixin implements AquiferAccess {
    @Shadow
    @Final
    Aquifer.FluidPicker val$fluidRule;

    @Override
    public int chunking$skipSamplingAboveY() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean chunking$isGlobalFluidAirRange(int bx, int minY, int maxY, int bz) {
        return this.val$fluidRule.computeFluid(bx, minY, bz).at(minY).is(Blocks.AIR)
                && this.val$fluidRule.computeFluid(bx, maxY, bz).at(maxY).is(Blocks.AIR);
    }

    @Override
    public BlockState chunking$globalFluidAt(int bx, int by, int bz) {
        return this.val$fluidRule.computeFluid(bx, by, bz).at(by);
    }

    @Override
    public BlockState chunking$getUniformBoxFluid(int x0, int y0, int z0, int cw, int ch) {
        if (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) {
            return null;
        }
        int endY = y0 + ch - 1;
        BlockState low = this.val$fluidRule.computeFluid(x0, y0, z0).at(y0);
        BlockState high = this.val$fluidRule.computeFluid(x0 + cw - 1, endY, z0 + cw - 1).at(endY);
        return low == high ? low : null;
    }

    @Override
    public boolean chunking$isPreparedAllEqual() {
        return false;
    }

    @Override
    public int chunking$preparedFluidLevel() {
        return Integer.MIN_VALUE;
    }

    @Override
    public BlockState chunking$preparedFluidType() {
        return null;
    }

    @Override
    public boolean chunking$hasPreparedCell() {
        return false;
    }

    @Override
    public boolean chunking$barrierNeedsNoiseChunk() {
        return false;
    }

    @Override
    public BlockState chunking$computeVoxelFromCell(
            DensityFunction.FunctionContext ctx,
            int bx,
            int by,
            int bz,
            double dens
    ) {
        return dens > 0.0D ? null : this.val$fluidRule.computeFluid(bx, by, bz).at(by);
    }

    @Override
    public boolean chunking$fillCellBuf(
            NoiseChunk noiseChunk,
            double[] fullVals,
            BlockState[] outStates,
            long[] outFluidUpdateMask,
            int x0,
            int y0,
            int z0,
            int cw,
            int ch,
            double invCw,
            double invCh,
            boolean barrierNeedsChunk
    ) {
        return false;
    }
}
