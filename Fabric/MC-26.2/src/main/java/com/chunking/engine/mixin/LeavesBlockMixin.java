package com.chunking.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.OptionalInt;

/**
 * Caches leaf distance {@link OptionalInt} instances and skips no-op {@code setBlock} calls during leaf ticks.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {
    @Unique
    private static final OptionalInt[] CHUNKING$CACHED_OPT_INTS = {
            OptionalInt.of(0), OptionalInt.of(1), OptionalInt.of(2), OptionalInt.of(3),
            OptionalInt.of(4), OptionalInt.of(5), OptionalInt.of(6), OptionalInt.of(7)
    };

    @Shadow
    @Final
    public static IntegerProperty DISTANCE;

    @Unique
    private static OptionalInt chunking$optOf(int dist) {
        return (dist >= 0 && dist < CHUNKING$CACHED_OPT_INTS.length)
                ? CHUNKING$CACHED_OPT_INTS[dist]
                : OptionalInt.of(dist);
    }

    @WrapMethod(method = "getOptionalDistanceAt")
    private static OptionalInt chunking$fastOptionalDistanceAt(
            BlockState state,
            Operation<OptionalInt> original
    ) {
        if (state.isAir()) {
            return OptionalInt.empty();
        }
        if (state.is(BlockTags.PREVENTS_NEARBY_LEAF_DECAY)) {
            return CHUNKING$CACHED_OPT_INTS[0];
        }
        if (state.hasProperty(DISTANCE)) {
            return chunking$optOf(state.getValue(DISTANCE));
        }
        return OptionalInt.empty();
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            ),
            require = 0
    )
    private boolean chunking$skipNoOpSetBlockOnLeafTick(
            ServerLevel level,
            BlockPos pos,
            BlockState newState,
            int flags,
            Operation<Boolean> original,
            BlockState currentState
    ) {
        if (newState == currentState) {
            return false;
        }
        return original.call(level, pos, newState, flags);
    }
}
