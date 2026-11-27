package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.mixin.accessor.StrategyAccessor;
import com.chunking.engine.access.PalettedContainerAccess;
import com.chunking.engine.access.PalettedDataAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Configuration;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.LongStream;

/**
 * Eliminates repeated linear/hashmap {@link Palette#idFor} and {@link Palette#valueFor} interface
 * lookups across consecutive runs of identical block states (stone/deepslate/air/water) in worldgen,
 * provides O(1) 0-bit {@link SingleValuePalette} writes for uniform biome sections,
 * and exposes direct cell box filling into underlying {@link BitStorage}.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> implements PalettedContainerAccess<T> {
    @Shadow
    @Final
    private Strategy<T> strategy;

    @Unique
    private static final MethodHandle CHUNKING$DATA_GETTER = chunking$resolveDataGetter();

    @Unique
    private static final MethodHandle CHUNKING$SINGLE_VAL_SETTER = chunking$resolveSingleValSetter();

    @Unique
    private static MethodHandle chunking$resolveDataGetter() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
            for (Field f : PalettedContainer.class.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("Data")) {
                    return lookup.unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique
    private static MethodHandle chunking$resolveSingleValSetter() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(SingleValuePalette.class, MethodHandles.lookup());
            for (Field f : SingleValuePalette.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == Object.class) {
                    return lookup.unreflectSetter(f).asType(MethodType.methodType(void.class, Object.class, Object.class));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Unique private Palette<T> chunking$lastPalette;
    @Unique private T chunking$lastVal;
    @Unique private int chunking$lastId;

    @Unique private Palette<T> chunking$lastPrevPalette;
    @Unique private int chunking$lastPrevId;
    @Unique private boolean chunking$hasPrevId;
    @Unique private T chunking$lastPrevVal;

    @Unique private boolean chunking$resizedDuringIdFor;

    @Unique private Palette<T> chunking$idCachePalette;
    @Unique private T chunking$idCacheAirVal;
    @Unique private int chunking$idCacheAirId;
    @Unique private T chunking$idCacheDefVal;
    @Unique private int chunking$idCacheDefId;

    @Unique private byte chunking$strategyKind;

    @Unique
    private byte chunking$initStrategyKind() {
        Strategy<T> s = this.strategy;
        byte kind;
        if (s.getIndex(1, 0, 0) == 1 && s.getIndex(0, 0, 1) == 16 && s.getIndex(0, 1, 0) == 256) {
            kind = 1;
        } else if (s.getIndex(1, 0, 0) == 1 && s.getIndex(0, 0, 1) == 4 && s.getIndex(0, 1, 0) == 16) {
            kind = 2;
        } else {
            kind = -1;
        }
        this.chunking$strategyKind = kind;
        return kind;
    }

    @Unique
    private static <E> E chunking$valueFor(Palette<E> palette, int id) {
        if (palette instanceof LinearPalette<E> linear) {
            return linear.valueFor(id);
        }
        if (palette instanceof HashMapPalette<E> hash) {
            return hash.valueFor(id);
        }
        if (palette instanceof SingleValuePalette<E> single) {
            return single.valueFor(id);
        }
        return palette.valueFor(id);
    }

    /**
     * @author Chunking
     * @reason Bypass Strategy.getIndex virtual/indirect dispatch for standard 16x16x16 block and 4x4x4 biome layouts.
     */
    @Overwrite
    public T get(int x, int y, int z) {
        byte kind = this.chunking$strategyKind;
        if (kind == 0) {
            kind = chunking$initStrategyKind();
        }
        int index;
        if (kind == 1) {
            index = (y << 8) | (z << 4) | x;
        } else if (kind == 2) {
            index = (y << 4) | (z << 2) | x;
        } else {
            index = this.strategy.getIndex(x, y, z);
        }
        return this.get(index);
    }

    /**
     * @author Chunking
     * @reason Lock-free single-read PalettedContainer.get fast path with ZeroBitStorage shortcut, division-free power-of-2 SimpleBitStorage bit extraction, and monomorphic palette dispatch.
     */
    @Overwrite
    public T get(int index) {
        PalettedDataAccess<T> d;
        try {
            d = chunking$data();
        } catch (Throwable t) {
            throw new IllegalStateException("PalettedContainer data unavailable", t);
        }
        BitStorage storage = d.chunking$storage();
        Palette<T> palette = d.chunking$palette();
        if (storage instanceof ZeroBitStorage) {
            return palette instanceof SingleValuePalette<T> single
                    ? single.valueFor(0)
                    : palette.valueFor(0);
        }
        int id;
        if (storage instanceof SimpleBitStorage simple) {
            int bits = simple.getBits();
            if (bits == 4) {
                id = (int) ((simple.getRaw()[index >>> 4] >>> ((index & 15) << 2)) & 15L);
            } else if (bits == 8) {
                id = (int) ((simple.getRaw()[index >>> 3] >>> ((index & 7) << 3)) & 255L);
            } else if (bits == 2) {
                id = (int) ((simple.getRaw()[index >>> 5] >>> ((index & 31) << 1)) & 3L);
            } else if (bits == 1) {
                id = (int) ((simple.getRaw()[index >>> 6] >>> (index & 63)) & 1L);
            } else {
                id = simple.get(index);
            }
        } else {
            id = storage.get(index);
        }
        return chunking$valueFor(palette, id);
    }

    @Unique
    private boolean chunking$isStandard4Bit(BitStorage storage) {
        if (storage.getBits() != 4) {
            return false;
        }
        long[] raw = storage.getRaw();
        Strategy<T> s = this.strategy;
        return raw != null && raw.length == 256
                && s.getIndex(1, 0, 0) == 1
                && s.getIndex(0, 0, 1) == 16
                && s.getIndex(0, 1, 0) == 256;
    }

    @Inject(method = "onResize", at = @At("HEAD"))
    private void chunking$invalidateCacheOnResize(int bits, T objectAdded, CallbackInfoReturnable<Integer> cir) {
        this.chunking$lastPalette = null;
        this.chunking$lastPrevPalette = null;
        this.chunking$hasPrevId = false;
        this.chunking$idCachePalette = null;
        this.chunking$resizedDuringIdFor = true;
    }

    @Override
    public boolean chunking$trySetUniformSingleValue(T value) {
        if (value == null || CHUNKING$DATA_GETTER == null) {
            return false;
        }
        try {
            PalettedDataAccess<T> d = chunking$data();
            if (!(d.chunking$storage() instanceof ZeroBitStorage)) {
                return false;
            }
            Palette<T> pal = d.chunking$palette();
            if (pal instanceof SingleValuePalette) {
                if (CHUNKING$SINGLE_VAL_SETTER != null) {
                    CHUNKING$SINGLE_VAL_SETTER.invokeExact((Object) pal, (Object) value);
                    java.lang.invoke.VarHandle.releaseFence();
                    this.chunking$lastPalette = null;
                    this.chunking$lastPrevPalette = null;
                    this.chunking$hasPrevId = false;
                    this.chunking$idCachePalette = null;
                    return true;
                }
                if (pal.valueFor(0) == value) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public T chunking$getSingleUniformValue() {
        if (CHUNKING$DATA_GETTER == null) {
            return null;
        }
        try {
            PalettedDataAccess<T> d = chunking$data();
            if (d.chunking$storage() instanceof ZeroBitStorage) {
                return d.chunking$palette().valueFor(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @WrapOperation(
            method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/Palette;idFor(Ljava/lang/Object;Lnet/minecraft/world/level/chunk/PaletteResize;)I"
            ),
            require = 0
    )
    private int chunking$cachedIdFor(
            Palette<T> palette,
            T value,
            PaletteResize<T> resize,
            Operation<Integer> original
    ) {
        if (ModConfig.SIMD_NOISE && palette == this.chunking$lastPalette && value == this.chunking$lastVal) {
            return this.chunking$lastId;
        }
        this.chunking$resizedDuringIdFor = false;
        int id = original.call(palette, value, resize);
        if (!this.chunking$resizedDuringIdFor) {
            this.chunking$lastPalette = palette;
            this.chunking$lastVal = value;
            this.chunking$lastId = id;
        }
        return id;
    }

    @WrapOperation(
            method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/Palette;valueFor(I)Ljava/lang/Object;"
            ),
            require = 0
    )
    private T chunking$cachedValueFor(
            Palette<T> palette,
            int id,
            Operation<T> original
    ) {
        if (ModConfig.SIMD_NOISE
                && this.chunking$hasPrevId
                && palette == this.chunking$lastPrevPalette
                && id == this.chunking$lastPrevId) {
            return this.chunking$lastPrevVal;
        }
        T val = original.call(palette, id);
        this.chunking$lastPrevPalette = palette;
        this.chunking$lastPrevId = id;
        this.chunking$hasPrevId = true;
        this.chunking$lastPrevVal = val;
        return val;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int chunking$fillCellBox(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cw,
            int ch,
            T value,
            T airValue,
            Consumer<T> nonAirOverwriteHandler
    ) {
        if (CHUNKING$DATA_GETTER == null) {
            return -1;
        }
        PalettedDataAccess<T> d;
        try {
            Object raw = CHUNKING$DATA_GETTER.invokeExact((Object) this);
            d = (PalettedDataAccess<T>) raw;
        } catch (Throwable t) {
            return -1;
        }
        if (d.chunking$storage() instanceof ZeroBitStorage && d.chunking$palette().valueFor(0) == value) {
            return 0;
        }
        Palette<T> curPal = d.chunking$palette();
        int newId;
        int airId;
        if (curPal == this.chunking$idCachePalette && value == this.chunking$idCacheDefVal && airValue == this.chunking$idCacheAirVal) {
            newId = this.chunking$idCacheDefId;
            airId = this.chunking$idCacheAirId;
        } else {
            PaletteResize<T> resize = (PaletteResize<T>) (Object) this;
            newId = curPal.idFor(value, resize);
            try {
                d = chunking$data();
            } catch (Throwable t) {
                return -1;
            }
            airId = d.chunking$palette().idFor(airValue, resize);
            try {
                PalettedDataAccess<T> dAfter = chunking$data();
                if (dAfter != d) {
                    d = dAfter;
                    newId = d.chunking$palette().idFor(value, resize);
                }
            } catch (Throwable t) {
                return -1;
            }
        }
        BitStorage storage = d.chunking$storage();
        if (storage instanceof ZeroBitStorage) {
            return 0;
        }
        Palette<T> palette = d.chunking$palette();
        this.chunking$idCachePalette = palette;
        this.chunking$idCacheAirVal = airValue;
        this.chunking$idCacheAirId = airId;
        this.chunking$idCacheDefVal = value;
        this.chunking$idCacheDefId = newId;
        if (cw == 4 && (localBaseX & 3) == 0 && chunking$isStandard4Bit(storage)) {
            long[] raw = storage.getRaw();
            int shift = localBaseX << 2;
            long rowMask = 0xFFFFL << shift;
            long invRowMask = ~rowMask;
            long newRow = ((long) newId * 0x1111L) << shift;
            long airRow = ((long) airId * 0x1111L) << shift;
            int addedNonAir = 0;
            for (int y = 0; y < ch; y++) {
                int yBase = (localBaseY + y) << 4;
                for (int z = 0; z < 4; z++) {
                    int wIdx = yBase | (localBaseZ + z);
                    long cur = raw[wIdx];
                    long prevGrp = cur & rowMask;
                    if (prevGrp == airRow) {
                        addedNonAir += 4;
                        raw[wIdx] = (cur & invRowMask) | newRow;
                    } else if (prevGrp != newRow) {
                        for (int x = 0; x < 4; x++) {
                            int s = shift + (x << 2);
                            int prev = (int) ((cur >>> s) & 0xFL);
                            if (prev == airId) {
                                addedNonAir++;
                            } else if (prev != newId && nonAirOverwriteHandler != null) {
                                nonAirOverwriteHandler.accept(palette.valueFor(prev));
                            }
                            cur = (cur & ~(0xFL << s)) | ((long) newId << s);
                        }
                        raw[wIdx] = cur;
                    }
                }
            }
            return addedNonAir;
        } else if (cw == 8 && (localBaseX & 7) == 0 && chunking$isStandard4Bit(storage)) {
            long[] raw = storage.getRaw();
            int shift = localBaseX << 2;
            long rowMask = 0xFFFFFFFFL << shift;
            long invRowMask = ~rowMask;
            long newRow = ((long) newId * 0x11111111L) << shift;
            long airRow = ((long) airId * 0x11111111L) << shift;
            int addedNonAir = 0;
            for (int y = 0; y < ch; y++) {
                int yBase = (localBaseY + y) << 4;
                for (int z = 0; z < 8; z++) {
                    int wIdx = yBase | (localBaseZ + z);
                    long cur = raw[wIdx];
                    long prevGrp = cur & rowMask;
                    if (prevGrp == airRow) {
                        addedNonAir += 8;
                        raw[wIdx] = (cur & invRowMask) | newRow;
                    } else if (prevGrp != newRow) {
                        for (int x = 0; x < 8; x++) {
                            int s = shift + (x << 2);
                            int prev = (int) ((cur >>> s) & 0xFL);
                            if (prev == airId) {
                                addedNonAir++;
                            } else if (prev != newId && nonAirOverwriteHandler != null) {
                                nonAirOverwriteHandler.accept(palette.valueFor(prev));
                            }
                            cur = (cur & ~(0xFL << s)) | ((long) newId << s);
                        }
                        raw[wIdx] = cur;
                    }
                }
            }
            return addedNonAir;
        }
        Strategy<T> strat = this.strategy;
        int addedNonAir = 0;
        for (int y = 0; y < ch; y++) {
            int ly = localBaseY + y;
            for (int z = 0; z < cw; z++) {
                int lz = localBaseZ + z;
                for (int x = 0; x < cw; x++) {
                    int idx = strat.getIndex(localBaseX + x, ly, lz);
                    int prev = storage.getAndSet(idx, newId);
                    if (prev == airId) {
                        addedNonAir++;
                    } else if (prev != newId && nonAirOverwriteHandler != null) {
                        nonAirOverwriteHandler.accept(palette.valueFor(prev));
                    }
                }
            }
        }
        return addedNonAir;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private PalettedDataAccess<T> chunking$data() throws Throwable {
        Object raw = CHUNKING$DATA_GETTER.invokeExact((Object) this);
        return (PalettedDataAccess<T>) raw;
    }

    @Override
    public Strategy<T> chunking$strategy() {
        return this.strategy;
    }

    @Override
    public PalettedDataAccess<T> chunking$dataAccess() {
        if (CHUNKING$DATA_GETTER == null) {
            return null;
        }
        try {
            return chunking$data();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public long chunking$fillMixedDensityCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cellBaseY,
            int cw,
            int ch,
            double[] fullVals,
            T defaultVal,
            T fluidVal,
            int fluidMaxExclusiveY,
            T airValue,
            Consumer<T> nonAirOverwriteHandler
    ) {
        if (CHUNKING$DATA_GETTER == null) {
            return -1L;
        }
        PalettedDataAccess<T> d;
        try {
            d = chunking$data();
        } catch (Throwable t) {
            return -1L;
        }
        Palette<T> curPal = d.chunking$palette();
        PaletteResize<T> resize = (PaletteResize<T>) (Object) this;
        int defaultId;
        int airId;
        int fluidId = -1;
        try {
            if (curPal == this.chunking$idCachePalette
                    && defaultVal == this.chunking$idCacheDefVal
                    && airValue == this.chunking$idCacheAirVal) {
                defaultId = this.chunking$idCacheDefId;
                airId = this.chunking$idCacheAirId;
            } else {
                defaultId = curPal.idFor(defaultVal, resize);
                d = chunking$data();
                airId = d.chunking$palette().idFor(airValue, resize);
                d = chunking$data();
            }
            if (fluidVal != null && fluidVal != airValue) {
                fluidId = d.chunking$palette().idFor(fluidVal, resize);
            }
            PalettedDataAccess<T> dAfter = chunking$data();
            if (dAfter != d) {
                d = dAfter;
                airId = d.chunking$palette().idFor(airValue, resize);
                defaultId = d.chunking$palette().idFor(defaultVal, resize);
                if (fluidVal != null && fluidVal != airValue) {
                    fluidId = d.chunking$palette().idFor(fluidVal, resize);
                }
            }
        } catch (Throwable t) {
            return -1L;
        }
        BitStorage storage = d.chunking$storage();
        if (storage instanceof ZeroBitStorage) {
            return -1L;
        }
        Palette<T> palette = d.chunking$palette();
        this.chunking$idCachePalette = palette;
        this.chunking$idCacheAirVal = airValue;
        this.chunking$idCacheAirId = airId;
        this.chunking$idCacheDefVal = defaultVal;
        this.chunking$idCacheDefId = defaultId;
        if ((cw == 4 || cw == 8) && (localBaseX & (cw - 1)) == 0 && chunking$isStandard4Bit(storage)) {
            long[] raw = storage.getRaw();
            int nonEmptyAdded = 0;
            int fluidWritten = 0;
            int baseShift = localBaseX << 2;
            int strideY = cw * cw;
            for (int inY = ch - 1; inY >= 0; inY--) {
                int yBase = (localBaseY + inY) << 4;
                int rowBase = (ch - 1 - inY) * strideY;
                boolean fluidAtY = fluidId >= 0 && (cellBaseY + inY) < fluidMaxExclusiveY;
                for (int inZ = 0; inZ < cw; inZ++) {
                    int wIdx = yBase | (localBaseZ + inZ);
                    long cur = raw[wIdx];
                    long updated = cur;
                    for (int inX = 0; inX < cw; inX++) {
                        double dens = fullVals[rowBase + inX * cw + inZ];
                        int targetId;
                        boolean isSolid;
                        if (dens > 0.0D) {
                            targetId = defaultId;
                            isSolid = true;
                        } else if (fluidAtY) {
                            targetId = fluidId;
                            isSolid = false;
                        } else {
                            continue;
                        }
                        int shift = baseShift + (inX << 2);
                        int prev = (int) ((updated >>> shift) & 0xFL);
                        if (prev == airId) {
                            nonEmptyAdded++;
                            if (!isSolid) {
                                fluidWritten++;
                            }
                        } else if (prev != targetId) {
                            if (nonAirOverwriteHandler != null) {
                                nonAirOverwriteHandler.accept(palette.valueFor(prev));
                            }
                            if (!isSolid) {
                                fluidWritten++;
                            }
                        }
                        updated = (updated & ~(0xFL << shift)) | ((long) targetId << shift);
                    }
                    if (updated != cur) {
                        raw[wIdx] = updated;
                    }
                }
            }
            return ((long) fluidWritten << 32) | (nonEmptyAdded & 0xFFFFFFFFL);
        }
        Strategy<T> strat = this.strategy;
        int nonEmptyAdded = 0;
        int fluidWritten = 0;
        int cellIdx = 0;
        for (int inY = ch - 1; inY >= 0; inY--) {
            int ly = localBaseY + inY;
            boolean fluidAtY = fluidId >= 0 && (cellBaseY + inY) < fluidMaxExclusiveY;
            for (int inX = 0; inX < cw; inX++) {
                int lx = localBaseX + inX;
                for (int inZ = 0; inZ < cw; inZ++) {
                    double dens = fullVals[cellIdx++];
                    int targetId;
                    boolean isSolid;
                    if (dens > 0.0D) {
                        targetId = defaultId;
                        isSolid = true;
                    } else if (fluidAtY) {
                        targetId = fluidId;
                        isSolid = false;
                    } else {
                        continue;
                    }
                    int idx = strat.getIndex(lx, ly, localBaseZ + inZ);
                    int prev = storage.getAndSet(idx, targetId);
                    if (prev == airId) {
                        nonEmptyAdded++;
                        if (!isSolid) {
                            fluidWritten++;
                        }
                    } else if (prev != targetId) {
                        if (nonAirOverwriteHandler != null) {
                            nonAirOverwriteHandler.accept(palette.valueFor(prev));
                        }
                        if (!isSolid) {
                            fluidWritten++;
                        }
                    }
                }
            }
        }
        return ((long) fluidWritten << 32) | (nonEmptyAdded & 0xFFFFFFFFL);
    }

    @Override
    @SuppressWarnings("unchecked")
    public int chunking$writeStateCell(
            int localBaseX,
            int localBaseY,
            int localBaseZ,
            int cw,
            int ch,
            T[] cellStates,
            T defaultVal,
            T airValue,
            int[] outExtraCounts
    ) {
        if (CHUNKING$DATA_GETTER == null) {
            return -1;
        }
        PalettedDataAccess<T> d;
        try {
            d = chunking$data();
        } catch (Throwable t) {
            return -1;
        }
        Palette<T> curPal = d.chunking$palette();
        PaletteResize<T> resize = (PaletteResize<T>) (Object) this;
        int airId;
        int defaultId;
        if (curPal == this.chunking$idCachePalette
                && defaultVal == this.chunking$idCacheDefVal
                && airValue == this.chunking$idCacheAirVal) {
            defaultId = this.chunking$idCacheDefId;
            airId = this.chunking$idCacheAirId;
        } else {
            try {
                airId = curPal.idFor(airValue, resize);
                d = chunking$data();
                defaultId = d.chunking$palette().idFor(defaultVal, resize);
                PalettedDataAccess<T> dAfter = chunking$data();
                if (dAfter != d) {
                    d = dAfter;
                    airId = d.chunking$palette().idFor(airValue, resize);
                    defaultId = d.chunking$palette().idFor(defaultVal, resize);
                }
            } catch (Throwable t) {
                return -1;
            }
        }
        BitStorage storage = d.chunking$storage();
        if (storage instanceof ZeroBitStorage) {
            return -1;
        }
        Palette<T> palette = d.chunking$palette();
        this.chunking$idCachePalette = palette;
        this.chunking$idCacheAirVal = airValue;
        this.chunking$idCacheAirId = airId;
        this.chunking$idCacheDefVal = defaultVal;
        this.chunking$idCacheDefId = defaultId;
        boolean fast4 = (cw == 4 || cw == 8) && (localBaseX & (cw - 1)) == 0 && chunking$isStandard4Bit(storage);
        long[] raw4 = fast4 ? storage.getRaw() : null;
        Strategy<T> strat = this.strategy;
        T lastVal = defaultVal;
        int lastId = defaultId;
        boolean defaultHasExtra = defaultVal instanceof net.minecraft.world.level.block.state.BlockState defBs
                && (defBs.isRandomlyTicking() || !defBs.getFluidState().isEmpty());
        int addedNonAir = 0;
        int tickingAdded = 0;
        int fluidAdded = 0;
        int tickingFluidAdded = 0;
        if (fast4) {
            int strideY = cw * cw;
            for (int inY = ch - 1; inY >= 0; inY--) {
                int ly = localBaseY + inY;
                int yBase = ly << 4;
                int rowBase = (ch - 1 - inY) * strideY;
                for (int inZ = 0; inZ < cw; inZ++) {
                    int wIdx = yBase | (localBaseZ + inZ);
                    long cur = fast4 ? raw4[wIdx] : 0L;
                    long updated = cur;
                    for (int inX = 0; inX < cw; inX++) {
                        T val = cellStates[rowBase + inX * cw + inZ];
                        if (val == null || val == airValue) {
                            continue;
                        }
                        int id;
                        if (val == defaultVal) {
                            id = defaultId;
                        } else if (val == lastVal) {
                            id = lastId;
                        } else {
                            if (fast4 && updated != cur) {
                                raw4[wIdx] = updated;
                                cur = updated;
                            }
                            id = palette.idFor(val, resize);
                            try {
                                PalettedDataAccess<T> dAfter = chunking$data();
                                if (dAfter != d) {
                                    d = dAfter;
                                    palette = d.chunking$palette();
                                    storage = d.chunking$storage();
                                    fast4 = (cw == 4 || cw == 8) && (localBaseX & (cw - 1)) == 0 && chunking$isStandard4Bit(storage);
                                    raw4 = fast4 ? storage.getRaw() : null;
                                    airId = palette.idFor(airValue, resize);
                                    defaultId = palette.idFor(defaultVal, resize);
                                    id = palette.idFor(val, resize);
                                    this.chunking$idCachePalette = palette;
                                    this.chunking$idCacheAirVal = airValue;
                                    this.chunking$idCacheAirId = airId;
                                    this.chunking$idCacheDefVal = defaultVal;
                                    this.chunking$idCacheDefId = defaultId;
                                    if (fast4) {
                                        cur = raw4[wIdx];
                                        updated = cur;
                                    }
                                }
                            } catch (Throwable t) {
                                return -1;
                            }
                            lastVal = val;
                            lastId = id;
                        }
                        int prev;
                        if (fast4) {
                            int shift = (localBaseX + inX) << 2;
                            prev = (int) ((updated >>> shift) & 0xFL);
                            updated = (updated & ~(0xFL << shift)) | ((long) id << shift);
                        } else {
                            prev = storage.getAndSet(strat.getIndex(localBaseX + inX, ly, localBaseZ + inZ), id);
                        }
                        if (prev == airId) {
                            if (val instanceof net.minecraft.world.level.block.state.BlockState bs) {
                                if (!bs.isAir()) {
                                    addedNonAir++;
                                }
                                if (defaultHasExtra || val != defaultVal) {
                                    if (bs.isRandomlyTicking()) {
                                        tickingAdded++;
                                    }
                                    net.minecraft.world.level.material.FluidState fs = bs.getFluidState();
                                    if (!fs.isEmpty()) {
                                        fluidAdded++;
                                        if (fs.isRandomlyTicking()) {
                                            tickingFluidAdded++;
                                        }
                                    }
                                }
                            } else {
                                addedNonAir++;
                            }
                        } else if (prev != id) {
                            boolean newIsAir = val instanceof net.minecraft.world.level.block.state.BlockState bsNew && bsNew.isAir();
                            T prevVal = palette.valueFor(prev);
                            if (prevVal instanceof net.minecraft.world.level.block.state.BlockState oldBs) {
                                if (oldBs.isAir()) {
                                    if (!newIsAir) {
                                        addedNonAir++;
                                    }
                                } else {
                                    if (newIsAir) {
                                        addedNonAir--;
                                    }
                                    if (oldBs.isRandomlyTicking()) {
                                        tickingAdded--;
                                    }
                                    net.minecraft.world.level.material.FluidState oldFs = oldBs.getFluidState();
                                    if (!oldFs.isEmpty()) {
                                        fluidAdded--;
                                        if (oldFs.isRandomlyTicking()) {
                                            tickingFluidAdded--;
                                        }
                                    }
                                }
                            }
                            if (val instanceof net.minecraft.world.level.block.state.BlockState bs) {
                                if (bs.isRandomlyTicking()) {
                                    tickingAdded++;
                                }
                                net.minecraft.world.level.material.FluidState fs = bs.getFluidState();
                                if (!fs.isEmpty()) {
                                    fluidAdded++;
                                    if (fs.isRandomlyTicking()) {
                                        tickingFluidAdded++;
                                    }
                                }
                            }
                        }
                    }
                    if (fast4 && updated != cur) {
                        raw4[wIdx] = updated;
                    }
                }
            }
        } else {
            int cellIdx = 0;
            for (int inY = ch - 1; inY >= 0; inY--) {
                int ly = localBaseY + inY;
                for (int inX = 0; inX < cw; inX++) {
                    int lx = localBaseX + inX;
                    for (int inZ = 0; inZ < cw; inZ++) {
                        T val = cellStates[cellIdx++];
                        if (val == null || val == airValue) {
                            continue;
                        }
                        int id;
                        if (val == defaultVal) {
                            id = defaultId;
                        } else if (val == lastVal) {
                            id = lastId;
                        } else {
                            id = palette.idFor(val, resize);
                            try {
                                PalettedDataAccess<T> dAfter = chunking$data();
                                if (dAfter != d) {
                                    d = dAfter;
                                    palette = d.chunking$palette();
                                    storage = d.chunking$storage();
                                    airId = palette.idFor(airValue, resize);
                                    defaultId = palette.idFor(defaultVal, resize);
                                    id = palette.idFor(val, resize);
                                }
                            } catch (Throwable t) {
                                return -1;
                            }
                            lastVal = val;
                            lastId = id;
                        }
                        int idx = strat.getIndex(lx, ly, localBaseZ + inZ);
                        int prev = storage.getAndSet(idx, id);
                        if (prev == airId) {
                            if (val instanceof net.minecraft.world.level.block.state.BlockState bs) {
                                if (!bs.isAir()) {
                                    addedNonAir++;
                                }
                                if (defaultHasExtra || val != defaultVal) {
                                    if (bs.isRandomlyTicking()) {
                                        tickingAdded++;
                                    }
                                    net.minecraft.world.level.material.FluidState fs = bs.getFluidState();
                                    if (!fs.isEmpty()) {
                                        fluidAdded++;
                                        if (fs.isRandomlyTicking()) {
                                            tickingFluidAdded++;
                                        }
                                    }
                                }
                            } else {
                                addedNonAir++;
                            }
                        } else if (prev != id) {
                            boolean newIsAir = val instanceof net.minecraft.world.level.block.state.BlockState bsNew && bsNew.isAir();
                            T prevVal = palette.valueFor(prev);
                            if (prevVal instanceof net.minecraft.world.level.block.state.BlockState oldBs) {
                                if (oldBs.isAir()) {
                                    if (!newIsAir) {
                                        addedNonAir++;
                                    }
                                } else {
                                    if (newIsAir) {
                                        addedNonAir--;
                                    }
                                    if (oldBs.isRandomlyTicking()) {
                                        tickingAdded--;
                                    }
                                    net.minecraft.world.level.material.FluidState oldFs = oldBs.getFluidState();
                                    if (!oldFs.isEmpty()) {
                                        fluidAdded--;
                                        if (oldFs.isRandomlyTicking()) {
                                            tickingFluidAdded--;
                                        }
                                    }
                                }
                            }
                            if (val instanceof net.minecraft.world.level.block.state.BlockState bs) {
                                if (bs.isRandomlyTicking()) {
                                    tickingAdded++;
                                }
                                net.minecraft.world.level.material.FluidState fs = bs.getFluidState();
                                if (!fs.isEmpty()) {
                                    fluidAdded++;
                                    if (fs.isRandomlyTicking()) {
                                        tickingFluidAdded++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        outExtraCounts[0] = tickingAdded;
        outExtraCounts[1] = fluidAdded;
        outExtraCounts[2] = tickingFluidAdded;
        return addedNonAir;
    }
}
