package com.chunking.engine.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RandomizedIntStateProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Captures this.property locally so concurrent lazy initialization cannot race.
 */
@Mixin(RandomizedIntStateProvider.class)
public abstract class RandomizedIntStateProviderMixin {
    @Shadow
    @Final
    private BlockStateProvider source;

    @Shadow
    @Final
    private String propertyName;

    @Shadow
    private IntegerProperty property;

    @Shadow
    @Final
    private IntProvider values;

    @Shadow
    private static IntegerProperty findProperty(BlockState state, String name) {
        throw new AssertionError();
    }

    /**
     * @author Chunking
     * @reason Thread-safe local capture of lazily initialized IntegerProperty.
     */
    @Overwrite
    public BlockState getState(WorldGenLevel level, RandomSource random, BlockPos pos) {
        BlockState state = this.source.getState(level, random, pos);
        IntegerProperty prop = this.property;
        if (prop == null || !state.hasProperty(prop)) {
            IntegerProperty resolved = findProperty(state, this.propertyName);
            if (resolved == null) {
                return state;
            }
            this.property = resolved;
            prop = resolved;
        }
        return state.setValue(prop, Integer.valueOf(this.values.sample(random)));
    }
}
