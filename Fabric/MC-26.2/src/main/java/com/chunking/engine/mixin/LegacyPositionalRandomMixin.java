package com.chunking.engine.mixin;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Returns lock-free {@link LegacyRandomSource} from
 * {@link LegacyRandomSource.LegacyPositionalRandomFactory#at(int, int, int)} and {@code fromHashOf}.
 */
@Mixin(LegacyRandomSource.LegacyPositionalRandomFactory.class)
public abstract class LegacyPositionalRandomMixin {
    @Shadow
    @Final
    private long seed;

    /**
     * @author Chunking
     * @reason Instantiate lock-free LegacyRandomSource.
     */
    @Overwrite
    public RandomSource at(int x, int y, int z) {
        return new LegacyRandomSource(Mth.getSeed(x, y, z) ^ this.seed);
    }

    /**
     * @author Chunking
     * @reason Instantiate lock-free LegacyRandomSource.
     */
    @Overwrite
    public RandomSource fromHashOf(String name) {
        return new LegacyRandomSource((long) name.hashCode() ^ this.seed);
    }
}
