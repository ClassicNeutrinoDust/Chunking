package com.chunking.engine.mixin;

import com.chunking.engine.access.BeardifierAccess;
import com.chunking.engine.world.ModCompat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Canonicalizes empty {@link Beardifier} instances to {@link Beardifier#EMPTY}, flattens vanilla
 * pieces/junctions into primitive arrays with tight AABB culling, and extracts tight union AABBs
 * for modded beardifiers (including Moog's {@code EnhancedBeardifierData}) so interval cell-skipping
 * stays active outside structure footprints.
 */
@Mixin(Beardifier.class)
public abstract class BeardifierMixin implements BeardifierAccess {
    @Unique
    private static final int CHUNKING$ADJ_NONE = 0;
    @Unique
    private static final int CHUNKING$ADJ_BURY = 1;
    @Unique
    private static final int CHUNKING$ADJ_BEARD_THIN = 2;
    @Unique
    private static final int CHUNKING$ADJ_BEARD_BOX = 3;
    @Unique
    private static final int CHUNKING$ADJ_ENCAPSULATE = 4;
    @Unique
    private static final int CHUNKING$KERNEL_RADIUS = 12;
    @Unique
    private static final int[] CHUNKING$EMPTY_INTS = new int[0];

    @Unique
    private static final Class<?> CHUNKING$MOOG_DATA_CLS;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_GET_PIECES;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_GET_JUNCTIONS;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_RIGID_BOX;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_RIGID_ADAPT;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_RIGID_ROT;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_JUNC_INNER;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_JUNC_ADAPT;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_ADAPT_RADIUS;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_ADAPT_SIZE;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_ADAPT_BOTTOM_OFFSET;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_ADAPT_PADDING;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_ADAPT_BAND;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_PAD_X;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_PAD_Z;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_PAD_TOP;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_PAD_BOTTOM;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_BAND_BOTTOM;
    @Unique
    private static final MethodHandle CHUNKING$MOOG_BAND_TOP;

    static {
        Class<?> dataCls = null;
        MethodHandle getPieces = null, getJunctions = null;
        MethodHandle rigidBox = null, rigidAdapt = null, rigidRot = null;
        MethodHandle juncInner = null, juncAdapt = null;
        MethodHandle adaptRadius = null, adaptSize = null, adaptBottomOffset = null, adaptPadding = null, adaptBand = null;
        MethodHandle padX = null, padZ = null, padTop = null, padBottom = null;
        MethodHandle bandBottom = null, bandTop = null;
        try {
            MethodHandles.Lookup lk = MethodHandles.publicLookup();
            dataCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.beardifier.EnhancedBeardifierData");
            Class<?> objListCls = Class.forName("it.unimi.dsi.fastutil.objects.ObjectList");
            Class<?> rigidCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.beardifier.EnhancedBeardifierRigid");
            Class<?> juncCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.beardifier.EnhancedJigsawJunction");
            Class<?> adaptCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.EnhancedTerrainAdaptation");
            Class<?> padCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.EnhancedTerrainAdaptation$Padding");
            Class<?> bandCls = Class.forName(
                    "com.finndog.moogs_structures.world.structures.terrainadaptation.EnhancedTerrainAdaptation$Band");

            getPieces = lk.findVirtual(dataCls, "moogs_structures_getEnhancedPieces", MethodType.methodType(objListCls));
            getJunctions = lk.findVirtual(dataCls, "moogs_structures_getEnhancedJunctions", MethodType.methodType(objListCls));
            rigidBox = lk.findVirtual(rigidCls, "pieceBoundingBox", MethodType.methodType(BoundingBox.class));
            rigidAdapt = lk.findVirtual(rigidCls, "pieceTerrainAdaptation", MethodType.methodType(adaptCls));
            rigidRot = lk.findVirtual(rigidCls, "rotation", MethodType.methodType(Rotation.class));
            juncInner = lk.findVirtual(juncCls, "jigsawJunction", MethodType.methodType(JigsawJunction.class));
            juncAdapt = lk.findVirtual(juncCls, "pieceTerrainAdaptation", MethodType.methodType(adaptCls));
            adaptRadius = lk.findVirtual(adaptCls, "getKernelRadius", MethodType.methodType(int.class));
            adaptSize = lk.findVirtual(adaptCls, "getKernelSize", MethodType.methodType(int.class));
            adaptBottomOffset = lk.findVirtual(adaptCls, "getBottomOffset", MethodType.methodType(double.class));
            adaptPadding = lk.findVirtual(adaptCls, "getPadding", MethodType.methodType(padCls));
            adaptBand = lk.findVirtual(adaptCls, "getBand", MethodType.methodType(Optional.class));
            padX = lk.findVirtual(padCls, "x", MethodType.methodType(int.class));
            padZ = lk.findVirtual(padCls, "z", MethodType.methodType(int.class));
            padTop = lk.findVirtual(padCls, "top", MethodType.methodType(int.class));
            padBottom = lk.findVirtual(padCls, "bottom", MethodType.methodType(int.class));
            bandBottom = lk.findVirtual(bandCls, "bottom", MethodType.methodType(int.class));
            bandTop = lk.findVirtual(bandCls, "top", MethodType.methodType(int.class));
        } catch (Throwable ignored) {
            dataCls = null;
        }
        CHUNKING$MOOG_DATA_CLS = dataCls;
        CHUNKING$MOOG_GET_PIECES = getPieces;
        CHUNKING$MOOG_GET_JUNCTIONS = getJunctions;
        CHUNKING$MOOG_RIGID_BOX = rigidBox;
        CHUNKING$MOOG_RIGID_ADAPT = rigidAdapt;
        CHUNKING$MOOG_RIGID_ROT = rigidRot;
        CHUNKING$MOOG_JUNC_INNER = juncInner;
        CHUNKING$MOOG_JUNC_ADAPT = juncAdapt;
        CHUNKING$MOOG_ADAPT_RADIUS = adaptRadius;
        CHUNKING$MOOG_ADAPT_SIZE = adaptSize;
        CHUNKING$MOOG_ADAPT_BOTTOM_OFFSET = adaptBottomOffset;
        CHUNKING$MOOG_ADAPT_PADDING = adaptPadding;
        CHUNKING$MOOG_ADAPT_BAND = adaptBand;
        CHUNKING$MOOG_PAD_X = padX;
        CHUNKING$MOOG_PAD_Z = padZ;
        CHUNKING$MOOG_PAD_TOP = padTop;
        CHUNKING$MOOG_PAD_BOTTOM = padBottom;
        CHUNKING$MOOG_BAND_BOTTOM = bandBottom;
        CHUNKING$MOOG_BAND_TOP = bandTop;
    }

    @Shadow
    @Final
    private List<Beardifier.Rigid> pieces;

    @Shadow
    @Final
    private List<JigsawJunction> junctions;

    @Shadow
    @Final
    @Nullable
    private BoundingBox affectedBox;

    @Shadow
    private static double getBuryContribution(double dx, double dy, double dz) {
        throw new AssertionError();
    }

    @Shadow
    private static double getBeardContribution(int dx, int dy, int dz, int yDiff) {
        throw new AssertionError();
    }

    // Initialized in <init> (mixin field)
    @Unique
    private boolean chunking$hasMoog;
    @Unique
    private boolean chunking$moogChecked;
    @Unique
    private boolean chunking$hasWork;
    @Unique
    private boolean chunking$hasUnionBox;
    @Unique
    private volatile boolean chunking$unionReady;

    @Unique
    private int chunking$uMinX;
    @Unique
    private int chunking$uMaxX;
    @Unique
    private int chunking$uMinY;
    @Unique
    private int chunking$uMaxY;
    @Unique
    private int chunking$uMinZ;
    @Unique
    private int chunking$uMaxZ;

    @Unique
    private int[] chunking$pMinX;
    @Unique
    private int[] chunking$pMaxX;
    @Unique
    private int[] chunking$pMinY;
    @Unique
    private int[] chunking$pMaxY;
    @Unique
    private int[] chunking$pMinZ;
    @Unique
    private int[] chunking$pMaxZ;
    @Unique
    private int[] chunking$pGroundY;
    @Unique
    private int[] chunking$pAdjKind;

    @Unique
    private int[] chunking$jX;
    @Unique
    private int[] chunking$jY;
    @Unique
    private int[] chunking$jZ;

    @Override
    @Nullable
    public BoundingBox chunking$getAffectedBox() {
        return this.affectedBox;
    }

    @Override
    @Nullable
    public BoundingBox chunking$getEffectiveUnionBox() {
        if (!chunking$hasUnionBox()) {
            return null;
        }
        return new BoundingBox(
                this.chunking$uMinX, this.chunking$uMinY, this.chunking$uMinZ,
                this.chunking$uMaxX, this.chunking$uMaxY, this.chunking$uMaxZ
        );
    }

    @Override
    public boolean chunking$hasUnionBox() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$hasUnionBox;
    }

    @Override
    public int chunking$uMinX() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$uMinX;
    }

    @Override
    public int chunking$uMaxX() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$uMaxX;
    }

    @Override
    public int chunking$uMinY() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$uMinY;
    }

    @Override
    public int chunking$uMaxY() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$uMaxY;
    }

    @Override
    public int chunking$uMinZ() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$uMinZ;
    }

    @Override
    public int chunking$uMaxZ() {
        if (!this.chunking$unionReady) {
            chunking$ensureUnionReady();
        }
        return this.chunking$uMaxZ;
    }

    @Override
    public boolean chunking$intersectsBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return chunking$hasUnionBox()
                && maxY >= this.chunking$uMinY && minY <= this.chunking$uMaxY
                && maxX >= this.chunking$uMinX && minX <= this.chunking$uMaxX
                && maxZ >= this.chunking$uMinZ && minZ <= this.chunking$uMaxZ;
    }

    @Unique
    private void chunking$ensureUnionReady() {
        if (!this.chunking$moogChecked) {
            this.chunking$hasMoog = ModCompat.hasMoogEnhancedBeardifier(this);
            this.chunking$moogChecked = true;
        }
        if (!this.chunking$hasMoog) {
            this.chunking$hasUnionBox = this.chunking$hasWork;
            this.chunking$unionReady = true;
            return;
        }
        this.chunking$hasUnionBox = chunking$computeMoogUnionBounds();
        this.chunking$unionReady = true;
    }

    @Unique
    private boolean chunking$computeMoogUnionBounds() {
        if (CHUNKING$MOOG_DATA_CLS == null || !CHUNKING$MOOG_DATA_CLS.isInstance(this)) {
            return false;
        }
        int uMinX = this.chunking$hasWork ? this.chunking$uMinX : Integer.MAX_VALUE;
        int uMaxX = this.chunking$hasWork ? this.chunking$uMaxX : Integer.MIN_VALUE;
        int uMinY = this.chunking$hasWork ? this.chunking$uMinY : Integer.MAX_VALUE;
        int uMaxY = this.chunking$hasWork ? this.chunking$uMaxY : Integer.MIN_VALUE;
        int uMinZ = this.chunking$hasWork ? this.chunking$uMinZ : Integer.MAX_VALUE;
        int uMaxZ = this.chunking$hasWork ? this.chunking$uMaxZ : Integer.MIN_VALUE;
        boolean any = this.chunking$hasWork;

        try {
            Object piecesObj = CHUNKING$MOOG_GET_PIECES.invoke(this);
            if (piecesObj instanceof Collection<?> col) {
                for (Object rigid : col) {
                    if (rigid == null) {
                        continue;
                    }
                    BoundingBox pb = (BoundingBox) CHUNKING$MOOG_RIGID_BOX.invoke(rigid);
                    Object adapt = CHUNKING$MOOG_RIGID_ADAPT.invoke(rigid);
                    Rotation rot = (Rotation) CHUNKING$MOOG_RIGID_ROT.invoke(rigid);
                    if (pb == null || adapt == null) {
                        return false;
                    }
                    int kr = (int) CHUNKING$MOOG_ADAPT_RADIUS.invoke(adapt);
                    int ks = (int) CHUNKING$MOOG_ADAPT_SIZE.invoke(adapt);
                    int radius = Math.max(CHUNKING$KERNEL_RADIUS, Math.max(kr, ks - kr));
                    int bottomOffset = (int) ((double) CHUNKING$MOOG_ADAPT_BOTTOM_OFFSET.invoke(adapt));
                    Object pad = CHUNKING$MOOG_ADAPT_PADDING.invoke(adapt);
                    int rawPadX = pad != null ? (int) CHUNKING$MOOG_PAD_X.invoke(pad) : 0;
                    int rawPadZ = pad != null ? (int) CHUNKING$MOOG_PAD_Z.invoke(pad) : 0;
                    boolean xAxis = rot == null || rot.rotate(Direction.EAST).getAxis() == Direction.Axis.X;
                    int padX = Math.max(0, xAxis ? rawPadX : rawPadZ);
                    int padZ = Math.max(0, xAxis ? rawPadZ : rawPadX);

                    int minX = pb.minX() - padX - radius;
                    int maxX = pb.maxX() + padX + radius;
                    int minZ = pb.minZ() - padZ - radius;
                    int maxZ = pb.maxZ() + padZ + radius;
                    int minY, maxY;
                    Optional<?> bandOpt = (Optional<?>) CHUNKING$MOOG_ADAPT_BAND.invoke(adapt);
                    if (bandOpt != null && bandOpt.isPresent()) {
                        Object band = bandOpt.get();
                        int baseY = pb.minY() + bottomOffset;
                        int bLo = baseY + (int) CHUNKING$MOOG_BAND_BOTTOM.invoke(band);
                        int bHi = baseY + (int) CHUNKING$MOOG_BAND_TOP.invoke(band);
                        minY = Math.min(bLo, bHi) - radius;
                        maxY = Math.max(bLo, bHi) + radius;
                    } else {
                        int padTop = pad != null ? (int) CHUNKING$MOOG_PAD_TOP.invoke(pad) : 0;
                        int padBot = pad != null ? (int) CHUNKING$MOOG_PAD_BOTTOM.invoke(pad) : 0;
                        minY = pb.minY() + bottomOffset - Math.max(0, padBot) - radius;
                        maxY = pb.maxY() + bottomOffset + Math.max(0, padTop) + radius;
                    }

                    if (minX < uMinX) uMinX = minX;
                    if (maxX > uMaxX) uMaxX = maxX;
                    if (minY < uMinY) uMinY = minY;
                    if (maxY > uMaxY) uMaxY = maxY;
                    if (minZ < uMinZ) uMinZ = minZ;
                    if (maxZ > uMaxZ) uMaxZ = maxZ;
                    any = true;
                }
            }

            Object juncObj = CHUNKING$MOOG_GET_JUNCTIONS.invoke(this);
            if (juncObj instanceof Collection<?> col) {
                for (Object ej : col) {
                    if (ej == null) {
                        continue;
                    }
                    JigsawJunction jj = (JigsawJunction) CHUNKING$MOOG_JUNC_INNER.invoke(ej);
                    Object adapt = CHUNKING$MOOG_JUNC_ADAPT.invoke(ej);
                    if (jj == null || adapt == null) {
                        return false;
                    }
                    Optional<?> bandOpt = (Optional<?>) CHUNKING$MOOG_ADAPT_BAND.invoke(adapt);
                    if (bandOpt != null && bandOpt.isPresent()) {
                        continue;
                    }
                    int kr = (int) CHUNKING$MOOG_ADAPT_RADIUS.invoke(adapt);
                    int ks = (int) CHUNKING$MOOG_ADAPT_SIZE.invoke(adapt);
                    int radius = Math.max(CHUNKING$KERNEL_RADIUS, Math.max(kr, ks - kr));
                    int jx = jj.getSourceX();
                    int jy = jj.getSourceGroundY() + (int) ((double) CHUNKING$MOOG_ADAPT_BOTTOM_OFFSET.invoke(adapt));
                    int jz = jj.getSourceZ();

                    if (jx - radius < uMinX) uMinX = jx - radius;
                    if (jx + radius > uMaxX) uMaxX = jx + radius;
                    if (jy - radius < uMinY) uMinY = jy - radius;
                    if (jy + radius > uMaxY) uMaxY = jy + radius;
                    if (jz - radius < uMinZ) uMinZ = jz - radius;
                    if (jz + radius > uMaxZ) uMaxZ = jz + radius;
                    any = true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        if (!any || uMinX > uMaxX || uMinY > uMaxY || uMinZ > uMaxZ) {
            return false;
        }
        this.chunking$uMinX = uMinX;
        this.chunking$uMaxX = uMaxX;
        this.chunking$uMinY = uMinY;
        this.chunking$uMaxY = uMaxY;
        this.chunking$uMinZ = uMinZ;
        this.chunking$uMaxZ = uMaxZ;
        return true;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initFlatData(CallbackInfo ci) {
        boolean moogNow = ModCompat.hasMoogEnhancedBeardifier(this);
        this.chunking$hasMoog = moogNow;
        // Re-check once on first compute() or union query in case Moog populates enhanced pieces after <init>.
        this.chunking$moogChecked = moogNow;
        this.chunking$unionReady = false;

        BoundingBox box = this.affectedBox;
        List<Beardifier.Rigid> pieceList = this.pieces;
        List<JigsawJunction> juncList = this.junctions;

        int activePieceCount = 0;
        if (pieceList != null) {
            for (int i = 0, n = pieceList.size(); i < n; i++) {
                Beardifier.Rigid piece = pieceList.get(i);
                if (piece != null && piece.terrainAdjustment() != TerrainAdjustment.NONE) {
                    activePieceCount++;
                }
            }
        }
        int juncCount = juncList != null ? juncList.size() : 0;

        if (box == null || (activePieceCount == 0 && juncCount == 0)) {
            this.chunking$pMinX = CHUNKING$EMPTY_INTS;
            this.chunking$pMaxX = CHUNKING$EMPTY_INTS;
            this.chunking$pMinY = CHUNKING$EMPTY_INTS;
            this.chunking$pMaxY = CHUNKING$EMPTY_INTS;
            this.chunking$pMinZ = CHUNKING$EMPTY_INTS;
            this.chunking$pMaxZ = CHUNKING$EMPTY_INTS;
            this.chunking$pGroundY = CHUNKING$EMPTY_INTS;
            this.chunking$pAdjKind = CHUNKING$EMPTY_INTS;
            this.chunking$jX = CHUNKING$EMPTY_INTS;
            this.chunking$jY = CHUNKING$EMPTY_INTS;
            this.chunking$jZ = CHUNKING$EMPTY_INTS;
            this.chunking$hasWork = false;
            return;
        }

        int uMinX = Integer.MAX_VALUE;
        int uMaxX = Integer.MIN_VALUE;
        int uMinY = Integer.MAX_VALUE;
        int uMaxY = Integer.MIN_VALUE;
        int uMinZ = Integer.MAX_VALUE;
        int uMaxZ = Integer.MIN_VALUE;

        if (activePieceCount > 0) {
            int[] pMinX = new int[activePieceCount];
            int[] pMaxX = new int[activePieceCount];
            int[] pMinY = new int[activePieceCount];
            int[] pMaxY = new int[activePieceCount];
            int[] pMinZ = new int[activePieceCount];
            int[] pMaxZ = new int[activePieceCount];
            int[] pGroundY = new int[activePieceCount];
            int[] pAdjKind = new int[activePieceCount];

            int idx = 0;
            for (int i = 0, n = pieceList.size(); i < n; i++) {
                Beardifier.Rigid piece = pieceList.get(i);
                if (piece == null) {
                    continue;
                }
                int kind = switch (piece.terrainAdjustment()) {
                    case NONE -> CHUNKING$ADJ_NONE;
                    case BURY -> CHUNKING$ADJ_BURY;
                    case BEARD_THIN -> CHUNKING$ADJ_BEARD_THIN;
                    case BEARD_BOX -> CHUNKING$ADJ_BEARD_BOX;
                    case ENCAPSULATE -> CHUNKING$ADJ_ENCAPSULATE;
                    default -> CHUNKING$ADJ_NONE;
                };
                if (kind == CHUNKING$ADJ_NONE) {
                    continue;
                }
                BoundingBox pb = piece.box();
                int minX = pb.minX();
                int maxX = pb.maxX();
                int minY = pb.minY();
                int maxY = pb.maxY();
                int minZ = pb.minZ();
                int maxZ = pb.maxZ();
                int groundY = minY + piece.groundLevelDelta();

                pMinX[idx] = minX;
                pMaxX[idx] = maxX;
                pMinY[idx] = minY;
                pMaxY[idx] = maxY;
                pMinZ[idx] = minZ;
                pMaxZ[idx] = maxZ;
                pGroundY[idx] = groundY;
                pAdjKind[idx] = kind;
                idx++;

                int loY = Math.min(minY, groundY) - CHUNKING$KERNEL_RADIUS;
                int hiY = Math.max(maxY, groundY) + CHUNKING$KERNEL_RADIUS;
                if (minX - CHUNKING$KERNEL_RADIUS < uMinX) uMinX = minX - CHUNKING$KERNEL_RADIUS;
                if (maxX + CHUNKING$KERNEL_RADIUS > uMaxX) uMaxX = maxX + CHUNKING$KERNEL_RADIUS;
                if (loY < uMinY) uMinY = loY;
                if (hiY > uMaxY) uMaxY = hiY;
                if (minZ - CHUNKING$KERNEL_RADIUS < uMinZ) uMinZ = minZ - CHUNKING$KERNEL_RADIUS;
                if (maxZ + CHUNKING$KERNEL_RADIUS > uMaxZ) uMaxZ = maxZ + CHUNKING$KERNEL_RADIUS;
            }

            this.chunking$pMinX = pMinX;
            this.chunking$pMaxX = pMaxX;
            this.chunking$pMinY = pMinY;
            this.chunking$pMaxY = pMaxY;
            this.chunking$pMinZ = pMinZ;
            this.chunking$pMaxZ = pMaxZ;
            this.chunking$pGroundY = pGroundY;
            this.chunking$pAdjKind = pAdjKind;
        } else {
            this.chunking$pMinX = CHUNKING$EMPTY_INTS;
            this.chunking$pMaxX = CHUNKING$EMPTY_INTS;
            this.chunking$pMinY = CHUNKING$EMPTY_INTS;
            this.chunking$pMaxY = CHUNKING$EMPTY_INTS;
            this.chunking$pMinZ = CHUNKING$EMPTY_INTS;
            this.chunking$pMaxZ = CHUNKING$EMPTY_INTS;
            this.chunking$pGroundY = CHUNKING$EMPTY_INTS;
            this.chunking$pAdjKind = CHUNKING$EMPTY_INTS;
        }

        if (juncCount > 0) {
            int[] jX = new int[juncCount];
            int[] jY = new int[juncCount];
            int[] jZ = new int[juncCount];
            for (int i = 0; i < juncCount; i++) {
                JigsawJunction junc = juncList.get(i);
                int x = junc.getSourceX();
                int y = junc.getSourceGroundY();
                int z = junc.getSourceZ();
                jX[i] = x;
                jY[i] = y;
                jZ[i] = z;
                if (x - CHUNKING$KERNEL_RADIUS < uMinX) uMinX = x - CHUNKING$KERNEL_RADIUS;
                if (x + CHUNKING$KERNEL_RADIUS > uMaxX) uMaxX = x + CHUNKING$KERNEL_RADIUS;
                if (y - CHUNKING$KERNEL_RADIUS < uMinY) uMinY = y - CHUNKING$KERNEL_RADIUS;
                if (y + CHUNKING$KERNEL_RADIUS > uMaxY) uMaxY = y + CHUNKING$KERNEL_RADIUS;
                if (z - CHUNKING$KERNEL_RADIUS < uMinZ) uMinZ = z - CHUNKING$KERNEL_RADIUS;
                if (z + CHUNKING$KERNEL_RADIUS > uMaxZ) uMaxZ = z + CHUNKING$KERNEL_RADIUS;
            }
            this.chunking$jX = jX;
            this.chunking$jY = jY;
            this.chunking$jZ = jZ;
        } else {
            this.chunking$jX = CHUNKING$EMPTY_INTS;
            this.chunking$jY = CHUNKING$EMPTY_INTS;
            this.chunking$jZ = CHUNKING$EMPTY_INTS;
        }

        // Intersect with vanilla affectedBox to preserve 100% vanilla culling semantics.
        if (box.minX() > uMinX) uMinX = box.minX();
        if (box.maxX() < uMaxX) uMaxX = box.maxX();
        if (box.minY() > uMinY) uMinY = box.minY();
        if (box.maxY() < uMaxY) uMaxY = box.maxY();
        if (box.minZ() > uMinZ) uMinZ = box.minZ();
        if (box.maxZ() < uMaxZ) uMaxZ = box.maxZ();

        this.chunking$uMinX = uMinX;
        this.chunking$uMaxX = uMaxX;
        this.chunking$uMinY = uMinY;
        this.chunking$uMaxY = uMaxY;
        this.chunking$uMinZ = uMinZ;
        this.chunking$uMaxZ = uMaxZ;
        this.chunking$hasWork = (uMinX <= uMaxX) && (uMinY <= uMaxY) && (uMinZ <= uMaxZ);
    }

    @Inject(method = "forStructuresInChunk", at = @At("RETURN"), cancellable = true)
    private static void chunking$canonicalizeEmptyBeardifier(
            StructureManager structureManager,
            ChunkPos chunkPos,
            CallbackInfoReturnable<Beardifier> cir
    ) {
        Beardifier b = cir.getReturnValue();
        if (b != null && b != Beardifier.EMPTY
                && ((BeardifierAccess) b).chunking$getAffectedBox() == null
                && !ModCompat.hasMoogEnhancedBeardifier(b)) {
            cir.setReturnValue(Beardifier.EMPTY);
        }
    }

    @WrapMethod(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D")
    private double chunking$fastCompute(DensityFunction.FunctionContext ctx, Operation<Double> original) {
        if (!this.chunking$moogChecked) {
            this.chunking$hasMoog = ModCompat.hasMoogEnhancedBeardifier(this);
            this.chunking$moogChecked = true;
        }
        if (this.chunking$hasMoog) {
            if (chunking$hasUnionBox()) {
                int y = ctx.blockY();
                if (y < this.chunking$uMinY || y > this.chunking$uMaxY) {
                    return 0.0D;
                }
                int x = ctx.blockX();
                if (x < this.chunking$uMinX || x > this.chunking$uMaxX) {
                    return 0.0D;
                }
                int z = ctx.blockZ();
                if (z < this.chunking$uMinZ || z > this.chunking$uMaxZ) {
                    return 0.0D;
                }
            }
            return original.call(ctx);
        }
        if (!this.chunking$hasWork) {
            return 0.0D;
        }
        int y = ctx.blockY();
        if (y < this.chunking$uMinY || y > this.chunking$uMaxY) {
            return 0.0D;
        }
        int x = ctx.blockX();
        if (x < this.chunking$uMinX || x > this.chunking$uMaxX) {
            return 0.0D;
        }
        int z = ctx.blockZ();
        if (z < this.chunking$uMinZ || z > this.chunking$uMaxZ) {
            return 0.0D;
        }

        double total = 0.0D;
        int[] pMinX = this.chunking$pMinX;
        int pieceLen = pMinX.length;
        if (pieceLen > 0) {
            int[] pMaxX = this.chunking$pMaxX;
            int[] pMinY = this.chunking$pMinY;
            int[] pMaxY = this.chunking$pMaxY;
            int[] pMinZ = this.chunking$pMinZ;
            int[] pMaxZ = this.chunking$pMaxZ;
            int[] pGroundY = this.chunking$pGroundY;
            int[] pAdjKind = this.chunking$pAdjKind;

            for (int i = 0; i < pieceLen; i++) {
                int dx = Math.max(0, Math.max(pMinX[i] - x, x - pMaxX[i]));
                if (dx >= CHUNKING$KERNEL_RADIUS) {
                    continue;
                }
                int dz = Math.max(0, Math.max(pMinZ[i] - z, z - pMaxZ[i]));
                if (dz >= CHUNKING$KERNEL_RADIUS) {
                    continue;
                }
                switch (pAdjKind[i]) {
                    case CHUNKING$ADJ_BURY -> {
                        if (dx < 6 && dz < 6) {
                            int dyFromGround = y - pGroundY[i];
                            if (dyFromGround > -12 && dyFromGround < 12
                                    && (4 * (dx * dx + dz * dz) + dyFromGround * dyFromGround) < 144) {
                                total += getBuryContribution(dx, (double) dyFromGround * 0.5D, dz);
                            }
                        }
                    }
                    case CHUNKING$ADJ_BEARD_THIN -> {
                        int dyFromGround = y - pGroundY[i];
                        if (dyFromGround >= -CHUNKING$KERNEL_RADIUS && dyFromGround < CHUNKING$KERNEL_RADIUS) {
                            total += getBeardContribution(dx, dyFromGround, dz, dyFromGround) * 0.8D;
                        }
                    }
                    case CHUNKING$ADJ_BEARD_BOX -> {
                        int groundY = pGroundY[i];
                        int dyBox = Math.max(0, Math.max(groundY - y, y - pMaxY[i]));
                        if (dyBox < CHUNKING$KERNEL_RADIUS) {
                            total += getBeardContribution(dx, dyBox, dz, y - groundY) * 0.8D;
                        }
                    }
                    case CHUNKING$ADJ_ENCAPSULATE -> {
                        int dyEncap = Math.max(0, Math.max(pMinY[i] - y, y - pMaxY[i]));
                        if (dyEncap < CHUNKING$KERNEL_RADIUS
                                && (dx * dx + dyEncap * dyEncap + dz * dz) < 144) {
                            total += getBuryContribution(
                                    (double) dx * 0.5D,
                                    (double) dyEncap * 0.5D,
                                    (double) dz * 0.5D
                            ) * 0.8D;
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        int[] jX = this.chunking$jX;
        int juncLen = jX.length;
        if (juncLen > 0) {
            int[] jY = this.chunking$jY;
            int[] jZ = this.chunking$jZ;
            for (int i = 0; i < juncLen; i++) {
                int dx = x - jX[i];
                if (dx < -CHUNKING$KERNEL_RADIUS || dx >= CHUNKING$KERNEL_RADIUS) {
                    continue;
                }
                int dz = z - jZ[i];
                if (dz < -CHUNKING$KERNEL_RADIUS || dz >= CHUNKING$KERNEL_RADIUS) {
                    continue;
                }
                int dy = y - jY[i];
                if (dy < -CHUNKING$KERNEL_RADIUS || dy >= CHUNKING$KERNEL_RADIUS) {
                    continue;
                }
                total += getBeardContribution(dx, dy, dz, dy) * 0.4D;
            }
        }

        return total;
    }
}
