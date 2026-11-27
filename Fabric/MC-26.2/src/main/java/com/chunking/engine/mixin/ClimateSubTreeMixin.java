package com.chunking.engine.mixin;

import com.chunking.engine.access.ClimateNodeAccess;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Captures {@code Climate$RTree$SubTree} children directly at construction time, eliminating
 * reflection {@code Field.get -> AccessibleObject.verifyAccess} checks during biome searches.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$SubTree")
public abstract class ClimateSubTreeMixin implements ClimateNodeAccess {
    @Unique
    private ClimateNodeAccess[] chunking$cachedChildren;

    @Inject(method = "<init>(Ljava/util/List;Ljava/util/List;)V", at = @At("RETURN"))
    private void chunking$captureChildrenDirect(
            List<Climate.Parameter> space,
            List<?> childrenList,
            CallbackInfo ci
    ) {
        int n = childrenList.size();
        ClimateNodeAccess[] arr = new ClimateNodeAccess[n];
        for (int i = 0; i < n; i++) {
            arr[i] = (ClimateNodeAccess) childrenList.get(i);
        }
        this.chunking$cachedChildren = arr;
    }

    @Override
    public ClimateNodeAccess[] chunking$children() {
        return this.chunking$cachedChildren;
    }
}
