package com.chunking.engine.mixin;

import com.chunking.engine.world.FastHeightmap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Set;

/**
 * Replaces vanilla per-block {@link Heightmap#primeHeightmaps(ChunkAccess, Set)} scans with
 * {@link FastHeightmap#primeHeightmaps(ChunkAccess, Set)} to skip empty sections and
 * direct-read non-empty sections without {@code MutableBlockPos} or {@code ObjectListIterator} churn.
 */
@Mixin(Heightmap.class)
public abstract class HeightmapMixin {
    /**
     * @author Chunking
     * @reason Section-wise empty-skipping heightmap priming.
     */
    @Overwrite
    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        FastHeightmap.primeHeightmaps(chunk, types);
    }
}
