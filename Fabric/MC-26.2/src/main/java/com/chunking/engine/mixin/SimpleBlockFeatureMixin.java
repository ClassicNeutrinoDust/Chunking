package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fast path for {@link SimpleBlockFeature#place} (which drives all MC 26.2 grass, flower, and
 * vegetation patches) avoiding per-call {@code origin.above()} allocations on double plants and
 * redundant {@code BlockState.getBlock()} lookups while keeping exact RNG and placement parity.
 */
@Mixin(SimpleBlockFeature.class)
public abstract class SimpleBlockFeatureMixin {
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> CHUNKING$ABOVE_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void chunking$fastPlace(
            FeaturePlaceContext<SimpleBlockConfiguration> context,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS || ((Object) this).getClass() != SimpleBlockFeature.class) {
            return;
        }
        SimpleBlockConfiguration config = context.config();
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        // Always evaluate toPlace() first so stateful/weighted providers consume identical RNG.
        BlockState state = config.toPlace().getOptionalState(level, context.random(), origin);
        if (state == null || !state.canSurvive(level, origin)) {
            cir.setReturnValue(Boolean.FALSE);
            return;
        }
        Block block = state.getBlock();
        if (block instanceof DoublePlantBlock) {
            BlockPos.MutableBlockPos above = CHUNKING$ABOVE_POS.get();
            above.set(origin.getX(), origin.getY() + 1, origin.getZ());
            if (!level.isEmptyBlock(above)) {
                cir.setReturnValue(Boolean.FALSE);
                return;
            }
            DoublePlantBlock.placeAt(level, state, origin, 2);
        } else if (block instanceof MossyCarpetBlock) {
            MossyCarpetBlock.placeAt(level, origin, level.getRandom(), 2);
        } else {
            level.setBlock(origin, state, 2);
        }
        if (config.scheduleTick()) {
            level.scheduleTick(origin, level.getBlockState(origin).getBlock(), 1);
        }
        cir.setReturnValue(Boolean.TRUE);
    }
}
