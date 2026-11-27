package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.NoiseChunkAccessor;
import com.chunking.engine.simd.NoiseJit;
import com.chunking.engine.access.FlatCacheAccess;
import com.chunking.engine.simd.JitSupport;
import com.chunking.engine.access.NoiseSliceEval;
import com.chunking.engine.access.SliceProviderAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Eliminates redundant vertical loop overhead in {@code NoiseChunk$FlatCache.fillArray} when
 * filling vertical slices at fixed {@code (blockX, blockZ)}, without mutating {@code interpolationCounter},
 * and exposes zero-allocation quart lookup for compiled JIT kernels.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$FlatCache")
public abstract class NoiseFlatCacheMixin implements FlatCacheAccess {
    @Shadow
    @Final
    NoiseChunk this$0;

    @Shadow
    @Final
    private DensityFunction noiseFiller;

    @Shadow
    @Final
    private double[] values;

    @Shadow
    @Final
    private int sizeXZ;

    @Unique
    private boolean chunking$pendingFill;

    @Unique
    private static final class FlatCoordBuf {
        int[] bx = new int[25];
        int[] by = new int[25];
        int[] bz = new int[25];

        void ensure(int n) {
            if (this.bx.length < n) {
                this.bx = new int[n];
                this.by = new int[n];
                this.bz = new int[n];
            }
        }
    }

    @Unique
    private static final ThreadLocal<FlatCoordBuf> CHUNKING$FLAT_BUF = ThreadLocal.withInitial(FlatCoordBuf::new);

    @ModifyVariable(
            method = "<init>",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$FlatCache;values:[D",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            ),
            argsOnly = true,
            ordinal = 0
    )
    private boolean chunking$deferFlatCacheFill(boolean fill) {
        if (fill && ModConfig.SIMD_NOISE) {
            this.chunking$pendingFill = true;
            return false;
        }
        return fill;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$batchFillFlatCache(CallbackInfo ci) {
        if (!this.chunking$pendingFill) {
            return;
        }
        this.chunking$pendingFill = false;
        int nXZ = this.sizeXZ;
        int count = nXZ * nXZ;
        FlatCoordBuf buf = CHUNKING$FLAT_BUF.get();
        buf.ensure(count);
        NoiseChunkAccessor acc = (NoiseChunkAccessor) this.this$0;
        int firstX = acc.chunking$getFirstNoiseX();
        int firstZ = acc.chunking$getFirstNoiseZ();
        for (int ix = 0; ix < nXZ; ix++) {
            int bx = QuartPos.toBlock(firstX + ix);
            for (int iz = 0; iz < nXZ; iz++) {
                int idx = ix + iz * nXZ;
                buf.bx[idx] = bx;
                buf.by[idx] = 0;
                buf.bz[idx] = QuartPos.toBlock(firstZ + iz);
            }
        }
        NoiseJit.BoundKernel bound =
                ((NoiseSliceEval) this.this$0).chunking$onFlatCacheInit(this, this.noiseFiller);
        if (bound != null) {
            bound.evalBatch(buf.bx, buf.by, buf.bz, this.values, count, this.this$0, false);
        } else {
            JitSupport.MutableContext ctx = JitSupport.borrowContext(0, 0, 0);
            for (int i = 0; i < count; i++) {
                ctx.set(buf.bx[i], 0, buf.bz[i]);
                this.values[i] = this.noiseFiller.compute(ctx);
            }
        }
    }

    @Shadow
    public abstract double compute(DensityFunction.FunctionContext context);

    @Override
    public double chunking$sampleQuartFast(int bx, int bz) {
        NoiseChunkAccessor acc = (NoiseChunkAccessor) this$0;
        int qx = QuartPos.fromBlock(bx) - acc.chunking$getFirstNoiseX();
        int qz = QuartPos.fromBlock(bz) - acc.chunking$getFirstNoiseZ();
        if (qx >= 0 && qz >= 0 && qx < this.sizeXZ && qz < this.sizeXZ) {
            return this.values[qx + qz * this.sizeXZ];
        }
        return this.noiseFiller.compute(JitSupport.borrowContext(bx, 0, bz));
    }

    @Override
    public DensityFunction chunking$flatNoiseFiller() {
        return this.noiseFiller;
    }

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void chunking$fillVerticalFlatOnce(double[] out, DensityFunction.ContextProvider provider, CallbackInfo ci) {
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
