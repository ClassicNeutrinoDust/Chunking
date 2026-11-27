package com.chunking.engine.mixin.accessor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

/**
 * Exposes {@link Heightmap#isOpaque} and {@link Heightmap#setHeight(int, int, int)} for
 * section-wise heightmap priming without reflection overhead.
 */
@Mixin(Heightmap.class)
public interface HeightmapAccessor {
    @Accessor("isOpaque")
    Predicate<BlockState> chunking$getIsOpaque();

    @Invoker("setHeight")
    void chunking$invokeSetHeight(int x, int z, int y);
}
