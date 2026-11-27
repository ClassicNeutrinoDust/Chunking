package com.chunking.engine.mixin;

import com.chunking.engine.access.ClimateParameterListAccess;
import com.chunking.engine.access.ClimateRTreeAccess;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;

@Mixin(Climate.ParameterList.class)
public abstract class ClimateParameterListMixin<T> implements ClimateParameterListAccess<T> {
    @Unique
    private volatile ClimateRTreeAccess<T> chunking$cachedIndex;

    @Override
    @SuppressWarnings("unchecked")
    public ClimateRTreeAccess<T> chunking$index() {
        ClimateRTreeAccess<T> idx = this.chunking$cachedIndex;
        if (idx != null) {
            return idx;
        }
        try {
            Field f = this.getClass().getDeclaredField("index");
            f.setAccessible(true);
            Object val = f.get(this);
            if (val instanceof ClimateRTreeAccess<?> acc) {
                idx = (ClimateRTreeAccess<T>) acc;
                this.chunking$cachedIndex = idx;
                return idx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
