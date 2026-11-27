package com.chunking.engine.mixin.accessor;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes internal bound {@code contents} reference of {@link HolderSet.Named} so cached tag
 * bitsets invalidate automatically if tags are reloaded/rebound.
 */
@Mixin(HolderSet.Named.class)
public interface HolderSetNamedAccessor {
    @Accessor("contents")
    List<Holder<?>> chunking$getContents();
}
