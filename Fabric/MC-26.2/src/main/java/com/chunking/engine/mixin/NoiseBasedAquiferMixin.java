package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.AquiferAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Exposes {@code skipSamplingAboveY} and global fluid air checks on {@code Aquifer$NoiseBasedAquifer},
 * eliminates per-voxel {@link MutableDouble} heap allocations inside {@code computeSubstance},
 * memoizes per-cell {@code getUniformBoxFluid} across {@code selectCellYZ} and {@code doFill},
 * and executes pre-tabulated distance-table evaluation across 100% of mixed-density aquifer cells
 * (including multi-window boundary cells via 48-bit candidate masks).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin implements AquiferAccess {
    @Unique
    private static final ThreadLocal<MutableDouble> CHUNKING$TL_MUTABLE_DOUBLE =
            ThreadLocal.withInitial(MutableDouble::new);

    @Unique
    private static final double CHUNKING$FLOW_SIM = 1.0D - (double) (144 - 100) / 25.0D;

    @Unique
    private static final class CellAquiferState {
        NoiseBasedAquiferMixin owner;
        NoiseBasedAquiferMixin boxOwner;
        int lastBoxX = Integer.MIN_VALUE;
        int lastBoxY = Integer.MIN_VALUE;
        int lastBoxZ = Integer.MIN_VALUE;
        int lastBoxCw = -1;
        int lastBoxCh = -1;
        BlockState lastBoxResult;
        boolean sameWindow;
        boolean allEqual;
        int uniformFluidLevel;
        BlockState uniformFluidType;
        int candCount;
        final int[] gx = new int[48];
        final int[] gy = new int[48];
        final int[] gz = new int[48];
        final int[] sx = new int[48];
        final int[] sy = new int[48];
        final int[] sz = new int[48];
        final Aquifer.FluidStatus[] status = new Aquifer.FluidStatus[48];
        final int[] tmpMinD2 = new int[48];
        final int[] tmpIdx = new int[48];
        final int[] dx2 = new int[48 * 4];
        final int[] dz2 = new int[48 * 4];
        final int[] dy2 = new int[48];
        final int[] dxy2 = new int[48];
        final long[] xCandMask = new long[4];
        final long[] zCandMask = new long[4];
        final MutableDouble dummyMd = new MutableDouble();
        final com.chunking.engine.simd.JitSupport.MutableContext barrierCtx =
                new com.chunking.engine.simd.JitSupport.MutableContext();

        BlockState cacheBoxResult(
                NoiseBasedAquiferMixin self,
                int x0, int y0, int z0, int cw, int ch,
                BlockState res
        ) {
            this.boxOwner = self;
            this.lastBoxX = x0;
            this.lastBoxY = y0;
            this.lastBoxZ = z0;
            this.lastBoxCw = cw;
            this.lastBoxCh = ch;
            this.lastBoxResult = res;
            return res;
        }
    }

    @Unique
    private static final ThreadLocal<CellAquiferState> CHUNKING$TL_CELL_AQ =
            ThreadLocal.withInitial(CellAquiferState::new);

    @Shadow @Final private net.minecraft.world.level.levelgen.DensityFunction barrierNoise;

    @Invoker("calculatePressure")
    protected abstract double chunking$calculatePressure(
            net.minecraft.world.level.levelgen.DensityFunction.FunctionContext ctx,
            MutableDouble barrierCache,
            Aquifer.FluidStatus s1,
            Aquifer.FluidStatus s2
    );

    @Shadow
    @Final
    private int skipSamplingAboveY;

    @Shadow
    @Final
    private Aquifer.FluidPicker globalFluidPicker;

    @Shadow @Final private Aquifer.FluidStatus[] aquiferCache;
    @Shadow @Final private long[] aquiferLocationCache;
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final private int minGridX;
    @Shadow @Final private int minGridY;
    @Shadow @Final private int minGridZ;
    @Shadow @Final private int gridSizeX;
    @Shadow @Final private int gridSizeZ;
    @Shadow private boolean shouldScheduleFluidUpdate;

    @Invoker("getIndex")
    protected abstract int chunking$getIndex(int gx, int gy, int gz);

    @Invoker("getAquiferStatus")
    protected abstract Aquifer.FluidStatus chunking$getAquiferStatus(int idx);

    @Redirect(
            method = "computeSubstance",
            at = @At(
                    value = "NEW",
                    target = "(D)Lorg/apache/commons/lang3/mutable/MutableDouble;"
            ),
            require = 0
    )
    private MutableDouble chunking$reuseMutableDouble(double initialValue) {
        if (!ModConfig.SIMD_NOISE) {
            return new MutableDouble(initialValue);
        }
        MutableDouble md = CHUNKING$TL_MUTABLE_DOUBLE.get();
        md.setValue(initialValue);
        return md;
    }

    @Override
    public int chunking$skipSamplingAboveY() {
        return this.skipSamplingAboveY;
    }

    @Override
    public boolean chunking$isGlobalFluidAirRange(int bx, int minY, int maxY, int bz) {
        BlockState low = this.globalFluidPicker.computeFluid(bx, minY, bz).at(minY);
        if (!low.is(Blocks.AIR)) {
            return false;
        }
        BlockState high = this.globalFluidPicker.computeFluid(bx, maxY, bz).at(maxY);
        return high.is(Blocks.AIR);
    }

    @Override
    public BlockState chunking$globalFluidAt(int bx, int by, int bz) {
        return this.globalFluidPicker.computeFluid(bx, by, bz).at(by);
    }

    @Override
    public BlockState chunking$getUniformBoxFluid(int x0, int y0, int z0, int cw, int ch) {
        CellAquiferState tl = CHUNKING$TL_CELL_AQ.get();
        if (tl.boxOwner == this
                && tl.lastBoxX == x0 && tl.lastBoxY == y0 && tl.lastBoxZ == z0
                && tl.lastBoxCw == cw && tl.lastBoxCh == ch) {
            return tl.lastBoxResult;
        }
        tl.owner = null;
        tl.allEqual = false;
        tl.candCount = 0;
        if (net.minecraft.SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) {
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        int endY = y0 + ch - 1;
        if (endY <= -55) {
            BlockState s0 = this.globalFluidPicker.computeFluid(x0, y0, z0).at(y0);
            if (s0.is(Blocks.LAVA)
                    && this.globalFluidPicker.computeFluid(x0 + cw - 1, endY, z0 + cw - 1).at(endY).is(Blocks.LAVA)) {
                return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, s0);
            }
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        if (y0 > this.skipSamplingAboveY) {
            BlockState low = this.globalFluidPicker.computeFluid(x0, y0, z0).at(y0);
            BlockState high = this.globalFluidPicker.computeFluid(x0 + cw - 1, endY, z0 + cw - 1).at(endY);
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, low == high ? low : null);
        }
        if (endY > this.skipSamplingAboveY) {
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        int gxLo = (x0 - 5) >> 4;
        int gxHi = ((x0 + cw - 6) >> 4) + 1;
        int gyLo = Math.floorDiv(y0 + 1, 12) - 1;
        int gyHi = Math.floorDiv(endY + 1, 12) + 1;
        int gzLo = (z0 - 5) >> 4;
        int gzHi = ((z0 + cw - 6) >> 4) + 1;

        int gridSizeY = this.aquiferLocationCache.length / (this.gridSizeX * this.gridSizeZ);
        if (gxLo < this.minGridX || gxHi >= this.minGridX + this.gridSizeX
                || gyLo < this.minGridY || gyHi >= this.minGridY + gridSizeY
                || gzLo < this.minGridZ || gzHi >= this.minGridZ + this.gridSizeZ) {
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }

        Aquifer.FluidStatus first = null;
        int maxFluidLevel = Integer.MIN_VALUE;
        boolean allEqual = true;

        int nx = gxHi - gxLo + 1;
        int ny = gyHi - gyLo + 1;
        int nz = gzHi - gzLo + 1;
        int count = nx * ny * nz;
        if (count <= 0 || count > 48) {
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        boolean sameWindow = (nx == 2 && ny == 3 && nz == 2);
        int gxLoAll = (x0 + cw - 6) >> 4;
        int gxHiAll = ((x0 - 5) >> 4) + 1;
        int gyLoAll = Math.floorDiv(endY + 1, 12) - 1;
        int gyHiAll = Math.floorDiv(y0 + 1, 12) + 1;
        int gzLoAll = (z0 + cw - 6) >> 4;
        int gzHiAll = ((z0 - 5) >> 4) + 1;
        int minOfMaxD2 = Integer.MAX_VALUE;
        int x1 = x0 + cw - 1;
        int z1 = z0 + cw - 1;
        int k = 0;
        for (int gx = gxLo; gx <= gxHi; gx++) {
            boolean commonX = gx >= gxLoAll && gx <= gxHiAll;
            for (int gy = gyLo; gy <= gyHi; gy++) {
                boolean commonXY = commonX && gy >= gyLoAll && gy <= gyHiAll;
                for (int gz = gzLo; gz <= gzHi; gz++) {
                    int idx = this.chunking$getIndex(gx, gy, gz);
                    long loc = this.aquiferLocationCache[idx];
                    if (loc == Long.MAX_VALUE) {
                        RandomSource rng = this.positionalRandomFactory.at(gx, gy, gz);
                        int rx = (gx << 4) + rng.nextInt(10);
                        int ry = gy * 12 + rng.nextInt(9);
                        int rz = (gz << 4) + rng.nextInt(10);
                        loc = BlockPos.asLong(rx, ry, rz);
                        this.aquiferLocationCache[idx] = loc;
                    }
                    int sx = BlockPos.getX(loc);
                    int sy = BlockPos.getY(loc);
                    int sz = BlockPos.getZ(loc);
                    int dxMin = sx < x0 ? (x0 - sx) : (sx > x1 ? (sx - x1) : 0);
                    int dyMin = sy < y0 ? (y0 - sy) : (sy > endY ? (sy - endY) : 0);
                    int dzMin = sz < z0 ? (z0 - sz) : (sz > z1 ? (sz - z1) : 0);
                    int minD2 = dxMin * dxMin + dyMin * dyMin + dzMin * dzMin;
                    if (commonXY && gz >= gzLoAll && gz <= gzHiAll) {
                        int dxMax = Math.max(Math.abs(x0 - sx), Math.abs(x1 - sx));
                        int dyMax = Math.max(Math.abs(y0 - sy), Math.abs(endY - sy));
                        int dzMax = Math.max(Math.abs(z0 - sz), Math.abs(z1 - sz));
                        int maxD2 = dxMax * dxMax + dyMax * dyMax + dzMax * dzMax;
                        if (maxD2 < minOfMaxD2) {
                            minOfMaxD2 = maxD2;
                        }
                    }
                    tl.gx[k] = gx;
                    tl.gy[k] = gy;
                    tl.gz[k] = gz;
                    tl.sx[k] = sx;
                    tl.sy[k] = sy;
                    tl.sz[k] = sz;
                    tl.tmpMinD2[k] = minD2;
                    tl.tmpIdx[k] = idx;
                    k++;
                }
            }
        }

        int cutoff68 = (minOfMaxD2 <= Integer.MAX_VALUE - 68) ? (minOfMaxD2 + 68) : Integer.MAX_VALUE;
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (tl.tmpMinD2[i] > cutoff68) {
                continue;
            }
            int gx = tl.gx[i];
            int gy = tl.gy[i];
            int gz = tl.gz[i];
            int idx = tl.tmpIdx[i];
            Aquifer.FluidStatus fs = this.aquiferCache[idx];
            if (fs == null) {
                fs = this.chunking$getAquiferStatus(idx);
            }
            if (fs.fluidLevel() > maxFluidLevel) {
                maxFluidLevel = fs.fluidLevel();
            }
            if (first == null) {
                first = fs;
            } else if (!first.equals(fs)) {
                allEqual = false;
            }
            tl.gx[kept] = gx;
            tl.gy[kept] = gy;
            tl.gz[kept] = gz;
            tl.sx[kept] = tl.sx[i];
            tl.sy[kept] = tl.sy[i];
            tl.sz[kept] = tl.sz[i];
            tl.status[kept] = fs;
            kept++;
        }
        if (first == null) {
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        if (y0 < -50
                || this.globalFluidPicker.computeFluid(x0, y0, z0).at(y0).is(Blocks.LAVA)
                || this.globalFluidPicker.computeFluid(x0, y0 - 1, z0).at(y0 - 1).is(Blocks.LAVA)
                || this.globalFluidPicker.computeFluid(x0 + cw - 1, endY, z0 + cw - 1).at(endY).is(Blocks.LAVA)) {
            if (kept > 0) {
                tl.owner = this;
                tl.allEqual = false;
                tl.sameWindow = sameWindow;
                tl.candCount = kept;
            }
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        if (allEqual) {
            if (y0 >= first.fluidLevel()) {
                return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, Blocks.AIR.defaultBlockState());
            }
            if (endY < first.fluidLevel()) {
                return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, first.fluidType());
            }
            if (kept > 0) {
                tl.owner = this;
                tl.allEqual = true;
                tl.uniformFluidLevel = first.fluidLevel();
                tl.uniformFluidType = first.fluidType();
                tl.sameWindow = sameWindow;
                tl.candCount = kept;
            }
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
        }
        if ((long) y0 >= (long) maxFluidLevel + 6L) {
            return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, Blocks.AIR.defaultBlockState());
        }
        if (kept > 0) {
            tl.owner = this;
            tl.allEqual = false;
            tl.sameWindow = sameWindow;
            tl.candCount = kept;
        }
        return tl.cacheBoxResult(this, x0, y0, z0, cw, ch, null);
    }

    @Override
    public boolean chunking$isPreparedAllEqual() {
        CellAquiferState tl = CHUNKING$TL_CELL_AQ.get();
        return tl.owner == this && tl.allEqual;
    }

    @Override
    public int chunking$preparedFluidLevel() {
        return CHUNKING$TL_CELL_AQ.get().uniformFluidLevel;
    }

    @Override
    public BlockState chunking$preparedFluidType() {
        return CHUNKING$TL_CELL_AQ.get().uniformFluidType;
    }

    @Override
    public boolean chunking$hasPreparedCell() {
        CellAquiferState tl = CHUNKING$TL_CELL_AQ.get();
        return tl.owner == this && tl.candCount > 0;
    }

    @Override
    public boolean chunking$barrierNeedsNoiseChunk() {
        return this.barrierNoise instanceof net.minecraft.world.level.levelgen.DensityFunctions.MarkerOrMarked
                || !com.chunking.engine.world.ModCompat.isVanillaClass(this.barrierNoise.getClass());
    }

    @Override
    public BlockState chunking$computeVoxelFromCell(
            net.minecraft.world.level.levelgen.DensityFunction.FunctionContext ctx,
            int bx,
            int by,
            int bz,
            double dens
    ) {
        if (dens > 0.0D) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        if (by > this.skipSamplingAboveY) {
            this.shouldScheduleFluidUpdate = false;
            return this.globalFluidPicker.computeFluid(bx, by, bz).at(by);
        }
        if (this.globalFluidPicker.computeFluid(bx, by, bz).at(by).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            return Blocks.LAVA.defaultBlockState();
        }

        CellAquiferState tl = CHUNKING$TL_CELL_AQ.get();
        net.minecraft.world.level.levelgen.DensityFunction.FunctionContext pCtx =
                (this.barrierNoise instanceof net.minecraft.world.level.levelgen.DensityFunctions.MarkerOrMarked
                        || !com.chunking.engine.world.ModCompat.isVanillaClass(this.barrierNoise.getClass()))
                        ? ctx
                        : tl.barrierCtx.set(bx, by, bz);
        int candCount = tl.candCount;
        int d1 = Integer.MAX_VALUE;
        int d2 = Integer.MAX_VALUE;
        int d3 = Integer.MAX_VALUE;
        int d4 = Integer.MAX_VALUE;
        Aquifer.FluidStatus s1 = null;
        Aquifer.FluidStatus s2 = null;
        Aquifer.FluidStatus s3 = null;
        Aquifer.FluidStatus s4 = null;

        if (tl.sameWindow) {
            for (int i = 0; i < candCount; i++) {
                int dx = tl.sx[i] - bx;
                int dy = tl.sy[i] - by;
                int dz = tl.sz[i] - bz;
                int dist = dx * dx + dy * dy + dz * dz;
                if (d1 >= dist) {
                    s4 = s3; s3 = s2; s2 = s1; s1 = tl.status[i];
                    d4 = d3; d3 = d2; d2 = d1; d1 = dist;
                } else if (d2 >= dist) {
                    s4 = s3; s3 = s2; s2 = tl.status[i];
                    d4 = d3; d3 = d2; d2 = dist;
                } else if (d3 >= dist) {
                    s4 = s3; s3 = tl.status[i];
                    d4 = d3; d3 = dist;
                } else if (d4 >= dist) {
                    s4 = tl.status[i];
                    d4 = dist;
                }
            }
        } else {
            int bxG = (bx - 5) >> 4;
            int byG = Math.floorDiv(by + 1, 12);
            int bzG = (bz - 5) >> 4;
            for (int i = 0; i < candCount; i++) {
                if (((tl.gx[i] - bxG) & ~1) != 0 || ((tl.gz[i] - bzG) & ~1) != 0) {
                    continue;
                }
                int rdy = tl.gy[i] - byG;
                if (rdy < -1 || rdy > 1) {
                    continue;
                }
                int dx = tl.sx[i] - bx;
                int dy = tl.sy[i] - by;
                int dz = tl.sz[i] - bz;
                int dist = dx * dx + dy * dy + dz * dz;
                if (d1 >= dist) {
                    s4 = s3; s3 = s2; s2 = s1; s1 = tl.status[i];
                    d4 = d3; d3 = d2; d2 = d1; d1 = dist;
                } else if (d2 >= dist) {
                    s4 = s3; s3 = s2; s2 = tl.status[i];
                    d4 = d3; d3 = d2; d2 = dist;
                } else if (d3 >= dist) {
                    s4 = s3; s3 = tl.status[i];
                    d4 = d3; d3 = dist;
                } else if (d4 >= dist) {
                    s4 = tl.status[i];
                    d4 = dist;
                }
            }
        }

        int diff12 = d2 - d1;
        BlockState b1 = s1.at(by);
        if (diff12 >= 25) {
            this.shouldScheduleFluidUpdate = (diff12 <= 44) && !s1.equals(s2);
            return b1;
        }
        double sim12 = 1.0D - (double) diff12 / 25.0D;
        if (b1.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(bx, by - 1, bz).at(by - 1).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = true;
            return b1;
        }
        MutableDouble md = tl.dummyMd;
        md.setValue(Double.NaN);
        double p12 = sim12 * this.chunking$calculatePressure(pCtx, md, s1, s2);
        if (dens + p12 > 0.0D) {
            this.shouldScheduleFluidUpdate = false;
            return null;
        }
        double sim13 = 1.0D - (double) (d3 - d1) / 25.0D;
        if (sim13 > 0.0D) {
            double p13 = sim12 * sim13 * this.chunking$calculatePressure(pCtx, md, s1, s3);
            if (dens + p13 > 0.0D) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        double sim23 = 1.0D - (double) (d3 - d2) / 25.0D;
        if (sim23 > 0.0D) {
            double p23 = sim12 * sim23 * this.chunking$calculatePressure(pCtx, md, s2, s3);
            if (dens + p23 > 0.0D) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
        }
        boolean u12 = !s1.equals(s2);
        boolean u23 = sim23 >= CHUNKING$FLOW_SIM && !s2.equals(s3);
        boolean u13 = sim13 >= CHUNKING$FLOW_SIM && !s1.equals(s3);
        if (u12 || u23 || u13) {
            this.shouldScheduleFluidUpdate = true;
        } else {
            double sim14 = 1.0D - (double) (d4 - d1) / 25.0D;
            this.shouldScheduleFluidUpdate = sim13 >= CHUNKING$FLOW_SIM && sim14 >= CHUNKING$FLOW_SIM && !s1.equals(s4);
        }
        return b1;
    }

    @Override
    public boolean chunking$fillCellBuf(
            net.minecraft.world.level.levelgen.NoiseChunk noiseChunk,
            double[] fullVals,
            BlockState[] outStates,
            long[] outFluidUpdateMask,
            int x0,
            int y0,
            int z0,
            int cw,
            int ch,
            double invCw,
            double invCh,
            boolean barrierNeedsChunk
    ) {
        CellAquiferState tl = CHUNKING$TL_CELL_AQ.get();
        int candCount = tl.candCount;
        if (tl.owner != this || candCount <= 0 || cw != 4 || ch > 16) {
            return false;
        }
        outFluidUpdateMask[0] = 0L;
        outFluidUpdateMask[1] = 0L;
        if (ch > 8) {
            outFluidUpdateMask[2] = 0L;
            outFluidUpdateMask[3] = 0L;
        }

        int[] sx = tl.sx;
        int[] sy = tl.sy;
        int[] sz = tl.sz;
        int[] gx = tl.gx;
        int[] gy = tl.gy;
        int[] gz = tl.gz;
        int[] dx2 = tl.dx2;
        int[] dz2 = tl.dz2;
        int[] dy2 = tl.dy2;
        int[] dxy2 = tl.dxy2;
        Aquifer.FluidStatus[] statuses = tl.status;
        boolean sameWin = tl.sameWindow;
        long[] xCandMask = tl.xCandMask;
        long[] zCandMask = tl.zCandMask;

        for (int i = 0; i < candCount; i++) {
            int base = i << 2;
            int dx0 = sx[i] - x0;
            int dx1 = dx0 - 1;
            int dx2v = dx0 - 2;
            int dx3 = dx0 - 3;
            dx2[base] = dx0 * dx0;
            dx2[base + 1] = dx1 * dx1;
            dx2[base + 2] = dx2v * dx2v;
            dx2[base + 3] = dx3 * dx3;
            int dz0 = sz[i] - z0;
            int dz1 = dz0 - 1;
            int dz2v = dz0 - 2;
            int dz3 = dz0 - 3;
            dz2[base] = dz0 * dz0;
            dz2[base + 1] = dz1 * dz1;
            dz2[base + 2] = dz2v * dz2v;
            dz2[base + 3] = dz3 * dz3;
        }
        if (!sameWin) {
            for (int c = 0; c < 4; c++) {
                int bxG = (x0 + c - 5) >> 4;
                int bzG = (z0 + c - 5) >> 4;
                long xm = 0L;
                long zm = 0L;
                for (int i = 0; i < candCount; i++) {
                    if (((gx[i] - bxG) & ~1) == 0) {
                        xm |= (1L << i);
                    }
                    if (((gz[i] - bzG) & ~1) == 0) {
                        zm |= (1L << i);
                    }
                }
                xCandMask[c] = xm;
                zCandMask[c] = zm;
            }
        }

        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        boolean globalFluidAllAir = (y0 + ch - 1 > this.skipSamplingAboveY)
                && this.chunking$isGlobalFluidAirRange(x0, y0, y0 + ch - 1, z0)
                && this.chunking$isGlobalFluidAirRange(x0 + 3, y0, y0 + ch - 1, z0 + 3);
        boolean canHaveGlobalLava = (y0 - 1 <= -50)
                || this.globalFluidPicker.computeFluid(x0, y0 - 1, z0).at(y0 - 1).is(Blocks.LAVA)
                || this.globalFluidPicker.computeFluid(x0 + 3, y0 - 1, z0 + 3).at(y0 - 1).is(Blocks.LAVA);
        MutableDouble md = tl.dummyMd;

        for (int inY = ch - 1; inY >= 0; inY--) {
            int by = y0 + inY;
            int rowBase = (ch - 1 - inY) << 4;
            int rowNegMask = 0;
            for (int k = 0; k < 16; k++) {
                if (fullVals[rowBase + k] <= 0.0D) {
                    rowNegMask |= (1 << k);
                }
            }
            if (rowNegMask == 0) {
                continue;
            }
            if (by > this.skipSamplingAboveY) {
                if (globalFluidAllAir) {
                    for (int k = 0; k < 16; k++) {
                        if ((rowNegMask & (1 << k)) != 0) {
                            outStates[rowBase + k] = air;
                        }
                    }
                } else {
                    for (int k = 0; k < 16; k++) {
                        if ((rowNegMask & (1 << k)) != 0) {
                            outStates[rowBase + k] = this.globalFluidPicker.computeFluid(
                                    x0 + (k >> 2), by, z0 + (k & 3)).at(by);
                        }
                    }
                }
                continue;
            }

            long yCandMask = 0L;
            if (!sameWin) {
                int byG = Math.floorDiv(by + 1, 12);
                for (int i = 0; i < candCount; i++) {
                    int dy = sy[i] - by;
                    dy2[i] = dy * dy;
                    int rdy = gy[i] - byG;
                    if (rdy >= -1 && rdy <= 1) {
                        yCandMask |= (1L << i);
                    }
                }
            } else {
                for (int i = 0; i < candCount; i++) {
                    int dy = sy[i] - by;
                    dy2[i] = dy * dy;
                }
            }
            boolean yUpdated = false;
            for (int inX = 0; inX < 4; inX++) {
                int xRowBits = (rowNegMask >>> (inX << 2)) & 0xF;
                if (xRowBits == 0) {
                    continue;
                }
                for (int i = 0; i < candCount; i++) {
                    dxy2[i] = dy2[i] + dx2[(i << 2) | inX];
                }
                long xyMask = sameWin ? 0L : (yCandMask & xCandMask[inX]);
                int bx = x0 + inX;
                boolean xUpdated = false;
                for (int inZ = 0; inZ < 4; inZ++) {
                    if ((xRowBits & (1 << inZ)) == 0) {
                        continue;
                    }
                    int cellIdx = rowBase + (inX << 2) + inZ;
                    int bz = z0 + inZ;
                    if (canHaveGlobalLava && this.globalFluidPicker.computeFluid(bx, by, bz).at(by).is(Blocks.LAVA)) {
                        outStates[cellIdx] = lava;
                        continue;
                    }

                    int d1 = Integer.MAX_VALUE;
                    int d2 = Integer.MAX_VALUE;
                    int d3 = Integer.MAX_VALUE;
                    int d4 = Integer.MAX_VALUE;
                    Aquifer.FluidStatus s1 = null;
                    Aquifer.FluidStatus s2 = null;
                    Aquifer.FluidStatus s3 = null;
                    Aquifer.FluidStatus s4 = null;
                    if (sameWin) {
                        for (int i = 0; i < candCount; i++) {
                            int dxy = dxy2[i];
                            if (dxy > d4) {
                                continue;
                            }
                            int dist = dxy + dz2[(i << 2) | inZ];
                            if (dist > d4) {
                                continue;
                            }
                            if (d1 >= dist) {
                                s4 = s3; s3 = s2; s2 = s1; s1 = statuses[i];
                                d4 = d3; d3 = d2; d2 = d1; d1 = dist;
                            } else if (d2 >= dist) {
                                s4 = s3; s3 = s2; s2 = statuses[i];
                                d4 = d3; d3 = d2; d2 = dist;
                            } else if (d3 >= dist) {
                                s4 = s3; s3 = statuses[i];
                                d4 = d3; d3 = dist;
                            } else {
                                s4 = statuses[i];
                                d4 = dist;
                            }
                        }
                    } else {
                        long m = xyMask & zCandMask[inZ];
                        while (m != 0L) {
                            int i = Long.numberOfTrailingZeros(m);
                            m &= m - 1L;
                            int dxy = dxy2[i];
                            if (dxy > d4) {
                                continue;
                            }
                            int dist = dxy + dz2[(i << 2) | inZ];
                            if (dist > d4) {
                                continue;
                            }
                            if (d1 >= dist) {
                                s4 = s3; s3 = s2; s2 = s1; s1 = statuses[i];
                                d4 = d3; d3 = d2; d2 = d1; d1 = dist;
                            } else if (d2 >= dist) {
                                s4 = s3; s3 = s2; s2 = statuses[i];
                                d4 = d3; d3 = d2; d2 = dist;
                            } else if (d3 >= dist) {
                                s4 = s3; s3 = statuses[i];
                                d4 = d3; d3 = dist;
                            } else {
                                s4 = statuses[i];
                                d4 = dist;
                            }
                        }
                    }

                    int diff12 = d2 - d1;
                    BlockState b1 = s1.at(by);
                    if (diff12 >= 25) {
                        outStates[cellIdx] = b1;
                        if (diff12 <= 44 && !s1.equals(s2) && !b1.getFluidState().isEmpty()) {
                            outFluidUpdateMask[cellIdx >> 6] |= (1L << (cellIdx & 63));
                        }
                        continue;
                    }
                    double sim12 = 1.0D - (double) diff12 / 25.0D;
                    if (canHaveGlobalLava && b1.is(Blocks.WATER)
                            && this.globalFluidPicker.computeFluid(bx, by - 1, bz).at(by - 1).is(Blocks.LAVA)) {
                        outStates[cellIdx] = b1;
                        if (!b1.getFluidState().isEmpty()) {
                            outFluidUpdateMask[cellIdx >> 6] |= (1L << (cellIdx & 63));
                        }
                        continue;
                    }
                    if (barrierNeedsChunk && (!s1.equals(s2) || !s1.equals(s3) || !s2.equals(s3))) {
                        if (!yUpdated) {
                            noiseChunk.updateForY(by, (double) inY * invCh);
                            yUpdated = true;
                        }
                        if (!xUpdated) {
                            noiseChunk.updateForX(bx, (double) inX * invCw);
                            xUpdated = true;
                        }
                        noiseChunk.updateForZ(bz, (double) inZ * invCw);
                    }
                    net.minecraft.world.level.levelgen.DensityFunction.FunctionContext pCtx =
                            barrierNeedsChunk ? noiseChunk : tl.barrierCtx.set(bx, by, bz);
                    md.setValue(Double.NaN);
                    double dens = fullVals[cellIdx];
                    double p12 = sim12 * this.chunking$calculatePressure(pCtx, md, s1, s2);
                    if (dens + p12 > 0.0D) {
                        outStates[cellIdx] = null;
                        continue;
                    }
                    double sim13 = 1.0D - (double) (d3 - d1) / 25.0D;
                    if (sim13 > 0.0D) {
                        double p13 = sim12 * sim13 * this.chunking$calculatePressure(pCtx, md, s1, s3);
                        if (dens + p13 > 0.0D) {
                            outStates[cellIdx] = null;
                            continue;
                        }
                    }
                    double sim23 = 1.0D - (double) (d3 - d2) / 25.0D;
                    if (sim23 > 0.0D) {
                        double p23 = sim12 * sim23 * this.chunking$calculatePressure(pCtx, md, s2, s3);
                        if (dens + p23 > 0.0D) {
                            outStates[cellIdx] = null;
                            continue;
                        }
                    }
                    outStates[cellIdx] = b1;
                    if (!b1.getFluidState().isEmpty()) {
                        boolean u12 = !s1.equals(s2);
                        boolean u23 = sim23 >= CHUNKING$FLOW_SIM && !s2.equals(s3);
                        boolean u13 = sim13 >= CHUNKING$FLOW_SIM && !s1.equals(s3);
                        if (u12 || u23 || u13
                                || (sim13 >= CHUNKING$FLOW_SIM
                                    && (1.0D - (double) (d4 - d1) / 25.0D) >= CHUNKING$FLOW_SIM
                                    && !s1.equals(s4))) {
                            outFluidUpdateMask[cellIdx >> 6] |= (1L << (cellIdx & 63));
                        }
                    }
                }
            }
        }
        return true;
    }
}
