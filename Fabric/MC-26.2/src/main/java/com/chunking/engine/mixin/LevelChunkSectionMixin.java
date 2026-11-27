package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.access.LevelChunkSectionAccess;
import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;
import com.chunking.engine.world.SectionCounts;
import net.minecraft.core.Holder;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Optimizes hot {@link LevelChunkSection#setBlockState} calls with direct lock-free
 * {@link SimpleBitStorage} access across all bit widths (1..15) using power-of-2 bit shifts for
 * 4/8-bit and exact division-free reciprocal indexing for arbitrary widths (5, 6, 7, ...),
 * C2-inlineable {@code CallbackInfoReturnable} elimination, 0-bit no-op short-circuits,
 * 2-entry palette ID/flag caches, and zero-work counter updates when old and new states share
 * air/ticking/fluid flags; reorders {@link LevelChunkSection#fillBiomesFromNoise} to iterate
 * {@code x -> z -> y}; supports bulk uniform/mixed cell writes; and replaces
 * {@link #recalcBlockCounts()} with an allocation-free branchless bit-packed histogram scanner.
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements LevelChunkSectionAccess, Consumer<BlockState> {
    @Unique
    private static final BlockState CHUNKING$AIR_STATE = Blocks.AIR.defaultBlockState();

    @Unique
    private static final ThreadLocal<int[]> CHUNKING$RECALC_OUT_TL = ThreadLocal.withInitial(() -> new int[4]);

    /**
     * Exact {@code valuesPerLong = 64 / bits} for {@link SimpleBitStorage} bit widths 1..15.
     */
    @Unique
    private static final int[] CHUNKING$VALUES_PER_LONG = {
            0, 64, 32, 21, 16, 12, 10, 9, 8, 7, 6, 5, 5, 4, 4, 4
    };

    /**
     * Exact reciprocal multipliers {@code ceil(65536 / (64 / bits))} for division-free
     * {@code cellIndex = (index * mul) >>> 16} across all {@code 0 <= index < 4096} and {@code bits} 1..15.
     */
    @Unique
    private static final int[] CHUNKING$DIV_MUL_16 = {
            0, 1024, 2048, 3121, 4096, 5462, 6554, 7282, 8192, 9363, 10923, 13108, 13108, 16384, 16384, 16384
    };

    @Shadow
    private short nonEmptyBlockCount;

    @Shadow
    private short fluidCount;

    @Shadow
    private short tickingBlockCount;

    @Shadow
    private short tickingFluidCount;

    @Shadow
    @Final
    private PalettedContainer<BlockState> states;

    @Shadow
    private PalettedContainerRO<Holder<Biome>> biomes;

    @Shadow
    public abstract BlockState setBlockState(int x, int y, int z, BlockState state, boolean check);

    // Non-static @Unique fields must have no inline initializers (JEP 513 / Mixin 0.8.7).
    @Unique
    private Palette<BlockState> chunking$lastPalette;
    @Unique
    private BlockState chunking$lastState;
    @Unique
    private int chunking$lastStateId;
    @Unique
    private byte chunking$lastStateFlags;
    @Unique
    private BlockState chunking$lastOldState;
    @Unique
    private int chunking$lastOldId;
    @Unique
    private byte chunking$lastOldFlags;
    @Unique
    private byte chunking$indexLayout;

    @Unique
    private static byte chunking$computeStateFlags(BlockState state) {
        if (state == CHUNKING$AIR_STATE) {
            return 0;
        }
        int flags = 0;
        if (!state.isAir()) {
            flags = 1;
            if (state.isRandomlyTicking()) {
                flags |= 2;
            }
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            flags |= 4;
            if (fluid.isRandomlyTicking()) {
                flags |= 8;
            }
        }
        return (byte) flags;
    }

    @Unique
    private void chunking$applyFlagDiff(byte oldFlags, byte newFlags) {
        int diff = oldFlags ^ newFlags;
        if ((diff & 1) != 0) {
            this.nonEmptyBlockCount += (short) ((newFlags & 1) != 0 ? 1 : -1);
        }
        if ((diff & 2) != 0) {
            this.tickingBlockCount += (short) ((newFlags & 2) != 0 ? 1 : -1);
        }
        if ((diff & 4) != 0) {
            this.fluidCount += (short) ((newFlags & 4) != 0 ? 1 : -1);
        }
        if ((diff & 8) != 0) {
            this.tickingFluidCount += (short) ((newFlags & 8) != 0 ? 1 : -1);
        }
    }

    @Unique
    private static BlockState chunking$valueFor(Palette<BlockState> palette, int id) {
        if (palette instanceof LinearPalette<BlockState> linear) {
            return linear.valueFor(id);
        }
        if (palette instanceof HashMapPalette<BlockState> hash) {
            return hash.valueFor(id);
        }
        if (palette instanceof SingleValuePalette<BlockState> single) {
            return single.valueFor(id);
        }
        return palette.valueFor(id);
    }

    /**
     * Kept intentionally tiny so HotSpot C2 always inlines it into {@code setBlockState} and
     * scalar-replaces {@link CallbackInfoReturnable} (eliminating per-call heap allocation).
     */
    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunking$fastSetBlockState(
            int x,
            int y,
            int z,
            BlockState state,
            boolean check,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (!ModConfig.SIMD_NOISE || MixinPlugin.isLithiumLoaded()) {
            return;
        }
        cir.setReturnValue(chunking$setBlockStateFast(x, y, z, state, check));
    }

    @Unique
    @SuppressWarnings("unchecked")
    private BlockState chunking$setBlockStateFast(int x, int y, int z, BlockState state, boolean check) {
        if (check || !((Object) this.states instanceof PalettedContainerAccess<?> rawAcc)) {
            return chunking$setBlockStateSlow(x, y, z, state, check);
        }
        PalettedContainerAccess<BlockState> palAcc = (PalettedContainerAccess<BlockState>) rawAcc;
        PalettedDataAccess<BlockState> d = palAcc.chunking$dataAccess();
        if (d == null) {
            return chunking$setBlockStateSlow(x, y, z, state, false);
        }
        BitStorage storage = d.chunking$storage();
        Palette<BlockState> palette = d.chunking$palette();
        if (!(storage instanceof SimpleBitStorage simple)) {
            return chunking$setBlockStateNonSimple(x, y, z, state, storage, palette);
        }

        // Resolve newId and precomputed counter flags using per-section 2-entry cache.
        int newId;
        byte newFlags;
        if (palette == this.chunking$lastPalette && state == this.chunking$lastState) {
            newId = this.chunking$lastStateId;
            newFlags = this.chunking$lastStateFlags;
        } else if (palette == this.chunking$lastPalette && state == this.chunking$lastOldState) {
            newId = this.chunking$lastOldId;
            newFlags = this.chunking$lastOldFlags;
        } else {
            newId = chunking$resolveNewIdSlow(palAcc, d, palette, state);
            newFlags = this.chunking$lastStateFlags;
            PalettedDataAccess<BlockState> dAfter = palAcc.chunking$dataAccess();
            if (dAfter != d) {
                if (dAfter == null || !(dAfter.chunking$storage() instanceof SimpleBitStorage s2)) {
                    return chunking$setBlockStateSlow(x, y, z, state, false);
                }
                d = dAfter;
                palette = d.chunking$palette();
                simple = s2;
            }
        }

        byte layout = this.chunking$indexLayout;
        if (layout == 0) {
            layout = chunking$initIndexLayout(palAcc);
        }
        int index = (layout == 1)
                ? ((y << 8) | (z << 4) | x)
                : palAcc.chunking$strategy().getIndex(x, y, z);

        // Division-free direct read/write for all SimpleBitStorage bit widths (1..15) with zero-write no-op exit.
        int bits = simple.getBits();
        int oldId;
        if (bits == 4) {
            long[] raw = simple.getRaw();
            if (raw != null && raw.length == 256) {
                int wIdx = index >>> 4;
                int shift = (index & 15) << 2;
                long word = raw[wIdx];
                oldId = (int) ((word >>> shift) & 0xFL);
                if (oldId == newId) {
                    return state;
                }
                raw[wIdx] = (word & ~(0xFL << shift)) | ((long) newId << shift);
            } else {
                oldId = simple.getAndSet(index, newId);
                if (oldId == newId) {
                    return state;
                }
            }
        } else if (bits == 8) {
            long[] raw = simple.getRaw();
            if (raw != null && raw.length == 512) {
                int wIdx = index >>> 3;
                int shift = (index & 7) << 3;
                long word = raw[wIdx];
                oldId = (int) ((word >>> shift) & 0xFFL);
                if (oldId == newId) {
                    return state;
                }
                raw[wIdx] = (word & ~(0xFFL << shift)) | ((long) newId << shift);
            } else {
                oldId = simple.getAndSet(index, newId);
                if (oldId == newId) {
                    return state;
                }
            }
        } else {
            oldId = chunking$getAndSetGeneralBits(simple, bits, index, newId);
            if (oldId == newId) {
                return state;
            }
        }

        // Resolve oldState and oldFlags from cache before falling back to palette lookup.
        BlockState oldState;
        byte oldFlags;
        if (oldId == this.chunking$lastOldId && this.chunking$lastOldState != null) {
            oldState = this.chunking$lastOldState;
            oldFlags = this.chunking$lastOldFlags;
        } else if (oldId == this.chunking$lastStateId && this.chunking$lastState != null) {
            oldState = this.chunking$lastState;
            oldFlags = this.chunking$lastStateFlags;
        } else {
            oldState = chunking$resolveOldStateSlow(palette, oldId);
            oldFlags = this.chunking$lastOldFlags;
        }

        // Common solid -> solid replacements (stone -> dirt/deepslate/ore) share flags == 1 and skip all counters.
        if (oldFlags != newFlags) {
            chunking$applyFlagDiff(oldFlags, newFlags);
        }
        return oldState;
    }

    @Unique
    private static int chunking$getAndSetGeneralBits(SimpleBitStorage simple, int bits, int index, int newId) {
        long[] raw = simple.getRaw();
        if (bits >= 1 && bits <= 15 && raw != null && (index & ~4095) == 0) {
            int vpl = CHUNKING$VALUES_PER_LONG[bits];
            int cellIndex = (index * CHUNKING$DIV_MUL_16[bits]) >>> 16;
            if (cellIndex < raw.length) {
                int bitIndex = (index - cellIndex * vpl) * bits;
                long mask = (1L << bits) - 1L;
                long word = raw[cellIndex];
                int oldId = (int) ((word >>> bitIndex) & mask);
                if (oldId != newId) {
                    raw[cellIndex] = (word & ~(mask << bitIndex)) | (((long) newId & mask) << bitIndex);
                }
                return oldId;
            }
        }
        return simple.getAndSet(index, newId);
    }

    @Unique
    private BlockState chunking$setBlockStateNonSimple(
            int x,
            int y,
            int z,
            BlockState state,
            BitStorage storage,
            Palette<BlockState> palette
    ) {
        // Uniform 0-bit section fast path: skip resize, write, and counters on no-op.
        if (storage instanceof ZeroBitStorage && palette.getSize() == 1) {
            BlockState single = palette instanceof SingleValuePalette<BlockState> sp
                    ? sp.valueFor(0)
                    : palette.valueFor(0);
            if (single == state) {
                return single;
            }
        }
        return chunking$setBlockStateSlow(x, y, z, state, false);
    }

    @Unique
    private BlockState chunking$setBlockStateSlow(int x, int y, int z, BlockState state, boolean check) {
        BlockState oldState = check
                ? this.states.getAndSet(x, y, z, state)
                : this.states.getAndSetUnchecked(x, y, z, state);
        if (oldState != state) {
            byte oldFlags = chunking$computeStateFlags(oldState);
            byte newFlags = chunking$computeStateFlags(state);
            if (oldFlags != newFlags) {
                chunking$applyFlagDiff(oldFlags, newFlags);
            }
        }
        return oldState;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private int chunking$resolveNewIdSlow(
            PalettedContainerAccess<BlockState> palAcc,
            PalettedDataAccess<BlockState> d,
            Palette<BlockState> palette,
            BlockState state
    ) {
        PaletteResize<BlockState> resize = (PaletteResize<BlockState>) (Object) this.states;
        int newId = palette.idFor(state, resize);
        PalettedDataAccess<BlockState> dAfter = palAcc.chunking$dataAccess();
        byte newFlags = chunking$computeStateFlags(state);
        if (dAfter != d) {
            this.chunking$lastPalette = (dAfter != null) ? dAfter.chunking$palette() : null;
            this.chunking$lastOldState = null;
        } else if (palette != this.chunking$lastPalette) {
            this.chunking$lastPalette = palette;
            this.chunking$lastOldState = null;
        }
        this.chunking$lastState = state;
        this.chunking$lastStateId = newId;
        this.chunking$lastStateFlags = newFlags;
        return newId;
    }

    @Unique
    private BlockState chunking$resolveOldStateSlow(Palette<BlockState> palette, int oldId) {
        BlockState oldState = chunking$valueFor(palette, oldId);
        byte oldFlags = chunking$computeStateFlags(oldState);
        this.chunking$lastOldId = oldId;
        this.chunking$lastOldState = oldState;
        this.chunking$lastOldFlags = oldFlags;
        return oldState;
    }

    @Unique
    private byte chunking$initIndexLayout(PalettedContainerAccess<BlockState> palAcc) {
        Strategy<BlockState> s = palAcc.chunking$strategy();
        byte layout = (s != null
                && s.getIndex(1, 0, 0) == 1
                && s.getIndex(0, 0, 1) == 16
                && s.getIndex(0, 1, 0) == 256) ? (byte) 1 : (byte) -1;
        this.chunking$indexLayout = layout;
        return layout;
    }

    @Inject(method = "recalcBlockCounts", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void chunking$fastRecalcBlockCounts(CallbackInfo ci) {
        if (!ModConfig.SIMD_NOISE
                || MixinPlugin.isLithiumLoaded()
                || !((Object) this.states instanceof PalettedContainerAccess<?> rawAcc)) {
            return;
        }
        PalettedDataAccess<BlockState> d =
                ((PalettedContainerAccess<BlockState>) rawAcc).chunking$dataAccess();
        if (d == null) {
            return;
        }
        int[] out = CHUNKING$RECALC_OUT_TL.get();
        if (SectionCounts.tryCountDirect(d.chunking$palette(), d.chunking$storage(), out)) {
            this.nonEmptyBlockCount = (short) out[0];
            this.tickingBlockCount = (short) out[1];
            this.fluidCount = (short) out[2];
            this.tickingFluidCount = (short) out[3];
            ci.cancel();
        }
    }

    @Inject(method = "fillBiomesFromNoise", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void chunking$fillBiomesXzyOrder(
            BiomeResolver resolver,
            Climate.Sampler sampler,
            int minQuartX,
            int minQuartY,
            int minQuartZ,
            CallbackInfo ci
    ) {
        if (!ModConfig.SIMD_NOISE || MixinPlugin.isNoisiumLoaded()) {
            return;
        }
        Holder<Biome> first = resolver.getNoiseBiome(minQuartX, minQuartY, minQuartZ, sampler);
        PalettedContainer<Holder<Biome>> container = null;
        for (int x = 0; x < 4; x++) {
            int qx = minQuartX + x;
            for (int z = 0; z < 4; z++) {
                int qz = minQuartZ + z;
                for (int y = 0; y < 4; y++) {
                    if (x == 0 && z == 0 && y == 0) {
                        continue;
                    }
                    Holder<Biome> b = resolver.getNoiseBiome(qx, minQuartY + y, qz, sampler);
                    if (container == null) {
                        if (b == first) {
                            continue;
                        }
                        container = this.biomes.recreate();
                        if (!((Object) container instanceof PalettedContainerAccess<?> cAcc
                                && ((PalettedContainerAccess<Holder<Biome>>) cAcc).chunking$trySetUniformSingleValue(first))) {
                            for (int px = 0; px <= x; px++) {
                                int zLim = (px == x) ? z : 3;
                                for (int pz = 0; pz <= zLim; pz++) {
                                    int yLim = (px == x && pz == z) ? (y - 1) : 3;
                                    for (int py = 0; py <= yLim; py++) {
                                        container.getAndSetUnchecked(px, py, pz, first);
                                    }
                                }
                            }
                        }
                    }
                    container.getAndSetUnchecked(x, y, z, b);
                }
            }
        }
        if (container == null) {
            if (this.biomes instanceof PalettedContainerAccess<?> acc
                    && ((PalettedContainerAccess<Holder<Biome>>) acc).chunking$trySetUniformSingleValue(first)) {
                ci.cancel();
                return;
            }
            container = this.biomes.recreate();
            if (!((Object) container instanceof PalettedContainerAccess<?> cAcc
                    && ((PalettedContainerAccess<Holder<Biome>>) cAcc).chunking$trySetUniformSingleValue(first))) {
                for (int x = 0; x < 4; x++) {
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            container.getAndSetUnchecked(x, y, z, first);
                        }
                    }
                }
            }
        }
        this.biomes = container;
        ci.cancel();
    }

    @Override
    public void accept(BlockState oldState) {
        if (oldState.isAir()) {
            // Overwriting CAVE_AIR / VOID_AIR (id != Blocks.AIR id) with a non-air state increases nonEmptyBlockCount.
            this.nonEmptyBlockCount++;
        } else {
            if (oldState.isRandomlyTicking()) {
                this.tickingBlockCount--;
            }
            FluidState fluidState = oldState.getFluidState();
            if (!fluidState.isEmpty()) {
                this.fluidCount--;
                if (fluidState.isRandomlyTicking()) {
                    this.tickingFluidCount--;
                }
            }
        }
    }

    @Unique
    private static final ThreadLocal<int[]> CHUNKING$EXTRA_COUNTS_TL = ThreadLocal.withInitial(() -> new int[3]);

    @Override
    @SuppressWarnings("unchecked")
    public void chunking$fillUniformSolidCell(int localBaseX, int localBaseY, int localBaseZ, int cw, int ch, BlockState state) {
        // Fast path assumes state is non-air, non-ticking, non-fluid; fall back to exact per-block counters otherwise.
        int added = (state.isAir() || state.isRandomlyTicking() || !state.getFluidState().isEmpty())
                ? -1
                : ((PalettedContainerAccess<BlockState>) this.states)
                        .chunking$fillCellBox(localBaseX, localBaseY, localBaseZ, cw, ch, state, CHUNKING$AIR_STATE, this);
        if (added < 0) {
            for (int y = 0; y < ch; y++) {
                int ly = localBaseY + y;
                for (int x = 0; x < cw; x++) {
                    int lx = localBaseX + x;
                    for (int z = 0; z < cw; z++) {
                        this.setBlockState(lx, ly, localBaseZ + z, state, false);
                    }
                }
            }
            return;
        }
        if (added > 0) {
            this.nonEmptyBlockCount += (short) added;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean chunking$fillMixedDensityCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cellBaseY,
            int cw,
            int ch,
            double[] fullVals,
            BlockState defaultState,
            BlockState fluidState,
            int fluidMaxExclusiveY
    ) {
        if ((defaultState != null && (defaultState.isRandomlyTicking() || !defaultState.getFluidState().isEmpty()
                || defaultState.isAir()))
                || (fluidState != null && fluidState != CHUNKING$AIR_STATE && fluidState.isAir())) {
            return false;
        }
        long res = ((PalettedContainerAccess<BlockState>) this.states)
                .chunking$fillMixedDensityCell(localBaseX, localBaseY, localBaseZ, cellBaseY, cw, ch,
                        fullVals, defaultState, fluidState, fluidMaxExclusiveY, CHUNKING$AIR_STATE, this);
        if (res < 0L) {
            return false;
        }
        int nonEmptyAdded = (int) res;
        int fluidAdded = (int) (res >>> 32);
        this.nonEmptyBlockCount += (short) nonEmptyAdded;
        if (fluidAdded > 0 && fluidState != null && fluidState != CHUNKING$AIR_STATE) {
            if (fluidState.isRandomlyTicking()) {
                this.tickingBlockCount += (short) fluidAdded;
            }
            FluidState fs = fluidState.getFluidState();
            if (!fs.isEmpty()) {
                this.fluidCount += (short) fluidAdded;
                if (fs.isRandomlyTicking()) {
                    this.tickingFluidCount += (short) fluidAdded;
                }
            }
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean chunking$writeStateCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cw,
            int ch,
            BlockState[] cellStates,
            BlockState defaultState
    ) {
        int[] extra = CHUNKING$EXTRA_COUNTS_TL.get();
        int added = ((PalettedContainerAccess<BlockState>) this.states)
                .chunking$writeStateCell(localBaseX, localBaseY, localBaseZ, cw, ch, cellStates, defaultState, CHUNKING$AIR_STATE, extra);
        if (added < 0) {
            return false;
        }
        this.nonEmptyBlockCount += (short) added;
        this.tickingBlockCount += (short) extra[0];
        this.fluidCount += (short) extra[1];
        this.tickingFluidCount += (short) extra[2];
        return true;
    }
}
