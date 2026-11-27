package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.world.FastSurface;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Eliminates repeated {@code HolderSet.contains()} hash/set probes inside
 * {@code SurfaceRules$BiomeConditionSource$1BiomeCondition.compute()} using a single-slot
 * last-biome identity cache per chunk.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition")
public abstract class SurfaceBiomeCheckMixin {
    @Unique
    private HolderSet<Biome> chunking$cachedSet;

    @Unique
    private Holder<Biome> chunking$lastBiome;

    @Unique
    private boolean chunking$lastMatch;

    /**
     * @author Chunking
     * @reason Single-slot biome Holder identity cache avoiding SetN.probe on consecutive columns and rows.
     */
    @Overwrite
    protected boolean compute() {
        HolderSet<Biome> set = this.chunking$cachedSet;
        if (set == null) {
            set = FastSurface.extractBiomeConditionBiomes(this);
            this.chunking$cachedSet = set;
        }
        Holder<Biome> cur = FastSurface.getContextBiome(this);
        if (ModConfig.FAST_SURFACE_RULES && cur != null && cur == this.chunking$lastBiome) {
            return this.chunking$lastMatch;
        }
        if (set == null) {
            return false;
        }
        boolean match = set.contains(cur);
        this.chunking$lastBiome = cur;
        this.chunking$lastMatch = match;
        return match;
    }
}
