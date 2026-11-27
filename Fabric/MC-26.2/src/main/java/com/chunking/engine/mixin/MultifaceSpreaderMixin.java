package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;

/**
 * Replaces nested {@code Direction.stream()} pipelines in {@link MultifaceSpreader} (~1.65 GB/12s of
 * {@code ReferencePipeline} + {@code Long} boxing during sculk/lichen feature generation) with direct loops.
 */
@Mixin(MultifaceSpreader.class)
public abstract class MultifaceSpreaderMixin {
    @Unique
    private static final Direction[] CHUNKING$DIRS = Direction.values();

    @Shadow
    @Final
    private MultifaceSpreader.SpreadConfig config;

    @Shadow
    private long spreadFromFaceTowardAllDirections(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            boolean postProcess
    ) {
        throw new AssertionError();
    }

    @Shadow
    public abstract Optional<MultifaceSpreader.SpreadPos> spreadFromFaceTowardDirection(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            Direction spreadDirection,
            boolean postProcess
    );

    @WrapMethod(method = "spreadAll")
    private long chunking$fastSpreadAll(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            boolean postProcess,
            Operation<Long> original
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return original.call(state, level, pos, postProcess);
        }
        long count = 0L;
        for (int i = 0; i < CHUNKING$DIRS.length; i++) {
            Direction dir = CHUNKING$DIRS[i];
            if (this.config.canSpreadFrom(state, dir)) {
                count += this.spreadFromFaceTowardAllDirections(state, level, pos, dir, postProcess);
            }
        }
        return count;
    }

    @WrapMethod(method = "spreadFromFaceTowardAllDirections")
    private long chunking$fastSpreadFromFaceTowardAllDirections(
            BlockState state,
            LevelAccessor level,
            BlockPos pos,
            Direction face,
            boolean postProcess,
            Operation<Long> original
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return original.call(state, level, pos, face, postProcess);
        }
        long count = 0L;
        for (int i = 0; i < CHUNKING$DIRS.length; i++) {
            if (this.spreadFromFaceTowardDirection(state, level, pos, face, CHUNKING$DIRS[i], postProcess).isPresent()) {
                count++;
            }
        }
        return count;
    }
}
