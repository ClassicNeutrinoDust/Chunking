package com.chunking.engine.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates {@code AtomicLong.compareAndSet} spin-loop bus locks in {@link LegacyRandomSource}
 * by maintaining a direct non-atomic 48-bit LCG seed.
 */
@Mixin(LegacyRandomSource.class)
public abstract class LegacyRandomMixin {
    @Unique
    private static final long CHUNKING$MULTIPLIER = 25214903917L;
    @Unique
    private static final long CHUNKING$INCREMENT = 11L;
    @Unique
    private static final long CHUNKING$SEED_MASK = (1L << 48) - 1L;

    @Shadow
    @Final
    private MarsagliaPolarGaussian gaussianSource;

    @Unique
    private long chunking$fastSeed;

    /**
     * @author Chunking
     * @reason Lock-free non-atomic seed update for per-chunk LegacyRandomSource instances.
     */
    @Overwrite
    public void setSeed(long seed) {
        this.chunking$fastSeed = (seed ^ CHUNKING$MULTIPLIER) & CHUNKING$SEED_MASK;
        this.gaussianSource.reset();
    }

    /**
     * @author Chunking
     * @reason Lock-free non-atomic 48-bit LCG step without AtomicLong CAS spin-loop.
     */
    @Overwrite
    public int next(int bits) {
        long nextSeed = (this.chunking$fastSeed * CHUNKING$MULTIPLIER + CHUNKING$INCREMENT) & CHUNKING$SEED_MASK;
        this.chunking$fastSeed = nextSeed;
        return (int) (nextSeed >>> (48 - bits));
    }

    /**
     * @author Chunking
     * @reason Return lock-free LegacyRandomSource on fork().
     */
    @Overwrite
    public RandomSource fork() {
        return new LegacyRandomSource(((LegacyRandomSource) (Object) this).nextLong());
    }
}
