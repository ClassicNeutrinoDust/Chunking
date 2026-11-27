package com.chunking.engine.mixin;

import com.chunking.engine.mixin.accessor.SurfaceContextAccessor;
import com.chunking.engine.access.SurfaceConditionAccess;
import com.chunking.engine.world.FastSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates millions of per-block {@code Boolean.valueOf} / {@code Boolean.booleanValue} boxing
 * operations and polymorphic {@code getContextLastUpdate()} virtual calls inside
 * {@code SurfaceRules$LazyCondition.test()} using a primitive {@code boolean} cache, direct
 * context timestamp reads, and monomorphic {@link FastSurface.LazyFastAccess}
 * XZ/Y fast paths.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$LazyCondition")
public abstract class SurfaceLazyConditionMixin
        implements SurfaceConditionAccess, FastSurface.LazyFastAccess {
    @Shadow
    private long lastUpdate;

    @Shadow
    protected abstract long getContextLastUpdate();

    @Shadow
    protected abstract boolean compute();

    @Unique
    private SurfaceContextAccessor chunking$ctxAcc;

    @Unique
    private int chunking$kind;

    @Unique
    private boolean chunking$cachedResult;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initFastContextFields(CallbackInfo ci) {
        this.chunking$ctxAcc = FastSurface.getLazyContextAccessor(this);
        this.chunking$kind = FastSurface.classifyLazyCondition(this);
    }

    /**
     * @author Chunking
     * @reason Split XZ (high cache-hit) and Y (high cache-miss) branches with primitive boolean caching and direct timestamp reads.
     */
    @Overwrite
    public boolean test() {
        if (!com.chunking.engine.config.ModConfig.FAST_SURFACE_RULES) {
            long cur = this.getContextLastUpdate();
            if (cur == this.lastUpdate) {
                return this.chunking$cachedResult;
            }
            this.lastUpdate = cur;
            boolean val = this.compute();
            this.chunking$cachedResult = val;
            return val;
        }
        int kind = this.chunking$kind;
        if (kind == 1) {
            long cur = this.chunking$ctxAcc.chunking$getLastUpdateXZ();
            if (cur == this.lastUpdate) {
                return this.chunking$cachedResult;
            }
            this.lastUpdate = cur;
            boolean val = this.compute();
            this.chunking$cachedResult = val;
            return val;
        }
        if (kind == 2) {
            long cur = this.chunking$ctxAcc.chunking$getLastUpdateY();
            if (cur == this.lastUpdate) {
                return this.chunking$cachedResult;
            }
            this.lastUpdate = cur;
            boolean val = this.compute();
            this.chunking$cachedResult = val;
            return val;
        }
        long cur = this.getContextLastUpdate();
        if (cur == this.lastUpdate) {
            return this.chunking$cachedResult;
        }
        this.lastUpdate = cur;
        boolean val = this.compute();
        this.chunking$cachedResult = val;
        return val;
    }

    @Override
    public boolean chunking$test() {
        return this.test();
    }

    @Override
    public int chunking$getLazyKind() {
        return this.chunking$kind;
    }

    @Override
    public SurfaceContextAccessor chunking$getLazyContext() {
        return this.chunking$ctxAcc;
    }

    @Override
    public boolean chunking$testXZ(long curXZ) {
        if (curXZ == this.lastUpdate) {
            return this.chunking$cachedResult;
        }
        boolean val = this.compute();
        this.chunking$cachedResult = val;
        this.lastUpdate = curXZ;
        return val;
    }

    @Override
    public boolean chunking$testY(long curY) {
        if (curY == this.lastUpdate) {
            return this.chunking$cachedResult;
        }
        boolean val = this.compute();
        this.chunking$cachedResult = val;
        this.lastUpdate = curY;
        return val;
    }
}
