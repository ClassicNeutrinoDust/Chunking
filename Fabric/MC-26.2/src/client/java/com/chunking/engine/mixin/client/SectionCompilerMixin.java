package com.chunking.engine.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips {@link FluidRenderer#tesselate} for completely submerged interior fluid blocks without
 * allocating {@code BlockPos.relative} or triggering Fabric API's {@code ScopedValue} +
 * {@code CallbackInfo} fluid rendering hooks when all 6 faces are occluded by the same fluid.
 * Hoists the thread-local probe lookup to at most once per {@code compile} call via a stack-local
 * {@link Share} reference.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> CHUNKING$FLUID_PROBE =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @WrapOperation(
            method = "compile",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"
            ),
            require = 0
    )
    private void chunking$skipSubmergedFluidTesselate(
            FluidRenderer fluidRenderer,
            BlockAndTintGetter region,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            Operation<Void> original,
            @Share("chunkingFluidProbe") LocalRef<BlockPos.MutableBlockPos> probeRef
    ) {
        if (region == null || pos == null || fluidState == null) {
            original.call(fluidRenderer, region, pos, output, blockState, fluidState);
            return;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        Fluid type = fluidState.getType();
        BlockPos.MutableBlockPos probe = probeRef.get();
        if (probe == null) {
            probe = CHUNKING$FLUID_PROBE.get();
            probeRef.set(probe);
        }

        // Check UP first so surface water short-circuits on the very first probe.
        if (chunking$hasRenderableFluidFace(region, probe.set(x, y + 1, z), fluidState, blockState, Direction.UP, type)
                || chunking$hasRenderableFluidFace(region, probe.set(x, y - 1, z), fluidState, blockState, Direction.DOWN, type)
                || chunking$hasRenderableFluidFace(region, probe.set(x, y, z - 1), fluidState, blockState, Direction.NORTH, type)
                || chunking$hasRenderableFluidFace(region, probe.set(x, y, z + 1), fluidState, blockState, Direction.SOUTH, type)
                || chunking$hasRenderableFluidFace(region, probe.set(x - 1, y, z), fluidState, blockState, Direction.WEST, type)
                || chunking$hasRenderableFluidFace(region, probe.set(x + 1, y, z), fluidState, blockState, Direction.EAST, type)) {
            original.call(fluidRenderer, region, pos, output, blockState, fluidState);
        }
    }

    @Unique
    private static boolean chunking$hasRenderableFluidFace(
            BlockAndTintGetter region,
            BlockPos.MutableBlockPos probe,
            FluidState selfFluid,
            BlockState selfBlock,
            Direction dir,
            Fluid selfType
    ) {
        BlockState neighborState = region.getBlockState(probe);
        FluidState nf = neighborState.getFluidState();
        if (selfType.isSame(nf.getType())) {
            return false;
        }
        // Vanilla FluidRenderer always renders UP when the block above isn't the same fluid.
        if (dir == Direction.UP) {
            return true;
        }
        if (neighborState.isSolidRender()) {
            return false;
        }
        return FluidRenderer.shouldRenderFace(selfFluid, selfBlock, dir, nf);
    }
}
