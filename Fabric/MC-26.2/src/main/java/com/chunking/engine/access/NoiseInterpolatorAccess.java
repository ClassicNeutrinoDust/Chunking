package com.chunking.engine.access;

/**
 * Direct accessor bridge onto {@code NoiseChunk$NoiseInterpolator} so compiled JIT kernels hoist cell
 * corner values into JVM double locals outside the inner cell voxel loop.
 */
public interface NoiseInterpolatorAccess {
    double chunking$getNoise000();

    double chunking$getNoise001();

    double chunking$getNoise100();

    double chunking$getNoise101();

    double chunking$getNoise010();

    double chunking$getNoise011();

    double chunking$getNoise110();

    double chunking$getNoise111();

    double chunking$evalUnaligned(int bx, int by, int bz);

    void chunking$selectCellYZ(int cellY, int cellZ);

    void chunking$setOreVeinOnly(boolean oreVeinOnly);

    boolean chunking$isOreVeinOnly();

    void chunking$setUnusedInNoiseFill(boolean unused);

    boolean chunking$isUnusedInNoiseFill();

    void chunking$setDeferOreExtras(boolean defer);

    boolean chunking$isDeferOreExtras();

    void chunking$swapSliceMasks();

    void chunking$ensureCellCornersFilled(net.minecraft.world.level.levelgen.NoiseChunk nc, int x0, int x1, int z0, int z1, int cellZ);

    void chunking$updateForY(double ty);

    void chunking$updateForX(double tx);

    void chunking$updateForZ(double tz);

    void chunking$recycleSlices();

    void chunking$ensureSlices(int cellCountY, int cellCountXZ);
}
