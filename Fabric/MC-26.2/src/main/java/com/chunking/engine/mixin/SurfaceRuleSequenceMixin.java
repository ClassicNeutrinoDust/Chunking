package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.SequenceRuleAccess;
import com.chunking.engine.world.FastSurface;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Eliminates the per-block {@link List#iterator()} allocation in {@code SurfaceRules.SequenceRule.tryApply}
 * by flattening the child rules into a plain array evaluated with an indexed loop.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
public abstract class SurfaceRuleSequenceMixin implements SequenceRuleAccess {
    @Shadow
    @Final
    private List<?> rules;

    @Unique
    private Object[] chunking$ruleArray;

    @Override
    public Object[] chunking$flatRules() {
        Object[] arr = this.chunking$ruleArray;
        if (arr == null) {
            arr = FastSurface.toRuleArray(this.rules);
            this.chunking$ruleArray = arr;
        }
        return arr;
    }

    /**
     * @author Chunking
     * @reason Flatten immutable SequenceRule list into an indexed array to remove List.iterator() overhead in surface sweeps.
     */
    @Overwrite
    public BlockState tryApply(int x, int y, int z) {
        if (!ModConfig.FAST_SURFACE_RULES) {
            return FastSurface.evalListFallback(this.rules, x, y, z);
        }
        return FastSurface.evalSequence(chunking$flatRules(), x, y, z);
    }
}
