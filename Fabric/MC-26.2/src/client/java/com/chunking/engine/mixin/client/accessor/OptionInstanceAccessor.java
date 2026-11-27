package com.chunking.engine.mixin.client.accessor;

import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {
    @Mutable
    @Accessor("values")
    void chunking$setValues(OptionInstance.ValueSet<T> values);

    @Mutable
    @Accessor("codec")
    void chunking$setCodec(Codec<T> codec);
}
