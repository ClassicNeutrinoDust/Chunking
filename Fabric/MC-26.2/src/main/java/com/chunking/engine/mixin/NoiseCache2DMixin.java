package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.Cache2DAccess;
import com.chunking.engine.simd.JitSupport;
import com.chunking.engine.access.SliceProviderAccess;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Replaces vanilla {@code NoiseChunk$Cache2D}'s single-entry cache with a zero-overhead 1-entry
 * fast check plus a lazily allocated 64-entry direct-mapped 2-array cache using unboxed
 * {@code @Overwrite} to eliminate {@code CallbackInfoReturnable<Double>} heap allocations.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$Cache2D")
public abstract class NoiseCache2DMixin implements Cache2DAccess {
    @Shadow
    @Final
    private DensityFunction function;

    @Shadow
    private long lastPos2D;

    @Shadow
    private double lastValue;

    @Unique
    private long[] chunking$keys;

    @Unique
    private double[] chunking$vals;

    @Override
    public DensityFunction chunking$wrapped2DFunction() {
        return this.function;
    }

    @Unique
    private double chunking$lookupOrCompute(int bx, int bz, DensityFunction.FunctionContext context) {
        long pos2D = ChunkPos.pack(bx, bz);
        if (this.lastPos2D == pos2D) {
            return this.lastValue;
        }
        long[] keys = this.chunking$keys;
        int slot = (int) (HashCommon.mix(pos2D) & 63L);
        if (keys != null) {
            if (keys[slot] == pos2D) {
                double val = this.chunking$vals[slot];
                this.lastPos2D = pos2D;
                this.lastValue = val;
                return val;
            }
        } else if (this.lastPos2D != ChunkPos.INVALID_CHUNK_POS) {
            keys = new long[64];
            Arrays.fill(keys, Long.MIN_VALUE);
            int prevSlot = (int) (HashCommon.mix(this.lastPos2D) & 63L);
            keys[prevSlot] = this.lastPos2D;
            double[] vals = new double[64];
            vals[prevSlot] = this.lastValue;
            this.chunking$keys = keys;
            this.chunking$vals = vals;
        }

        double val = this.function.compute(
                context != null ? context : JitSupport.borrowContext(bx, 0, bz));
        this.lastPos2D = pos2D;
        this.lastValue = val;
        if (keys != null) {
            keys[slot] = pos2D;
            this.chunking$vals[slot] = val;
        }
        return val;
    }

    @Override
    public double chunking$sample2DFast(int bx, int bz) {
        return chunking$lookupOrCompute(bx, bz, null);
    }

    /**
     * @author Chunking
     * @reason Eliminate CallbackInfoReturnable&lt;Double&gt; heap boxing on Cache2D.compute.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        if (!ModConfig.SIMD_NOISE) {
            long pos2D = ChunkPos.pack(x, z);
            if (this.lastPos2D == pos2D) {
                return this.lastValue;
            }
            this.lastPos2D = pos2D;
            double val = this.function.compute(context);
            this.lastValue = val;
            return val;
        }
        return chunking$lookupOrCompute(x, z, context);
    }

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void chunking$fillVerticalSliceOnce(double[] out, DensityFunction.ContextProvider provider, CallbackInfo ci) {
        if (!ModConfig.SIMD_NOISE || out == null || out.length <= 1) {
            return;
        }
        if (provider instanceof SliceProviderAccess sliceProvider) {
            double val = this.compute(sliceProvider.chunking$getNoiseChunk());
            Arrays.fill(out, val);
            ci.cancel();
        }
    }
}
