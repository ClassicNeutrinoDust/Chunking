package com.chunking.engine.world;

import com.chunking.engine.mixin.accessor.LegacyPositionalAccessor;
import com.chunking.engine.mixin.accessor.SurfaceContextAccessor;
import com.chunking.engine.mixin.accessor.XoroshiroPositionalAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import com.chunking.engine.access.SequenceRuleAccess;
import com.chunking.engine.access.SurfaceConditionAccess;

/**
 * Bridge into {@link SurfaceRules} protected types enabling algebraic dead-branch pruning,
 * nested sequence flattening, monomorphic compiled condition evaluation, per-column XZ
 * dead-rule bitmask pruning, cached biome checks, and zero-allocation
 * {@code VerticalGradientCondition} evaluation.
 */
public final class FastSurface extends SurfaceRules {
    /**
     * Monomorphic fast-path bridge implemented solely by {@code SurfaceRules$LazyCondition}
     * via {@code SurfaceLazyConditionMixin}, allowing HotSpot C2 to devirtualize and
     * inline split XZ/Y cache checks with pre-loaded context timestamps.
     */
    public interface LazyFastAccess {
        int chunking$getLazyKind();

        SurfaceContextAccessor chunking$getLazyContext();

        boolean chunking$testXZ(long curXZ);

        boolean chunking$testY(long curY);
    }

    private static final int COND_GENERIC = 0;
    private static final int COND_LAZY_XZ = 1;
    private static final int COND_LAZY_Y = 2;
    private static final int COND_VERT_GRAD = 3;
    private static final int COND_BIOME = 4;
    private static final int COND_RAW_COND = 5;

    private static final MethodHandle LAZY_CONTEXT_GETTER = resolveLazyContextGetter();
    private static final MethodHandle BIOME_COND_THIS0_GETTER = resolveBiomeCondThis0Getter();
    private static final MethodHandle BIOME_SRC_BIOMES_GETTER = resolveBiomeSrcBiomesGetter();
    private static final MethodHandle TEST_RULE_CTOR = resolveTestRuleCtor();
    private static final MethodHandle NOT_COND_CTOR = resolveNotCondCtor();
    private static final Class<?> STATE_RULE_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$StateRule");
    private static final Class<?> LAZY_XZ_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$LazyXZCondition");
    private static final Class<?> LAZY_Y_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$LazyYCondition");
    private static final Class<?> COND_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$Condition");
    private static final MethodHandle COND_TEST_MH = resolveCondTestHandle();
    private static final Class<?> NOT_COND_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$NotCondition");
    private static final Class<?> BIOME_COND_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition");
    private static final Class<?> VERT_GRAD_COND_CLASS = resolveClass("net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition");
    private static final MethodHandle NOT_COND_TARGET_GETTER = resolveNotCondTargetGetter();
    private static final MethodHandle VERT_GRAD_TRUE_GETTER = resolveVertGradIntGetter("val$trueAtAndBelow");
    private static final MethodHandle VERT_GRAD_FALSE_GETTER = resolveVertGradIntGetter("val$falseAtAndAbove");
    private static final MethodHandle VERT_GRAD_RNG_GETTER = resolveVertGradRngGetter();

    // Optional Lithostitched condition-source fast folding (null when mod is absent -> C2 DCEs checks).
    private static final Class<?> LITHO_BIOME_SRC_CLASS = resolveClass("dev.worldgen.lithostitched.impl.worldgen.surface.condition.BiomeCondition");
    private static final MethodHandle LITHO_BIOME_GETTER = resolveZeroArgGetter(LITHO_BIOME_SRC_CLASS, "biomes", HolderSet.class);
    private static final Class<?> LITHO_ALL_OF_SRC_CLASS = resolveClass("dev.worldgen.lithostitched.impl.worldgen.surface.condition.AllOfCondition");
    private static final MethodHandle LITHO_ALL_OF_GETTER = resolveZeroArgGetter(LITHO_ALL_OF_SRC_CLASS, "conditions", List.class);
    private static final MethodHandle LITHO_ALL_OF_COND_CTOR = resolveListCtor("dev.worldgen.lithostitched.impl.worldgen.surface.condition.AllOfCondition$Condition");
    private static final Class<?> LITHO_ANY_OF_SRC_CLASS = resolveClass("dev.worldgen.lithostitched.impl.worldgen.surface.condition.AnyOfCondition");
    private static final MethodHandle LITHO_ANY_OF_GETTER = resolveZeroArgGetter(LITHO_ANY_OF_SRC_CLASS, "conditions", List.class);
    private static final MethodHandle LITHO_ANY_OF_COND_CTOR = resolveListCtor("dev.worldgen.lithostitched.impl.worldgen.surface.condition.AnyOfCondition$Condition");

    public static final SurfaceRule NEVER_MATCH_RULE = (x, y, z) -> null;

    private static volatile Object alwaysFalseCondition = synthesizeConstCondition(false);
    private static volatile Object alwaysTrueCondition = synthesizeConstCondition(true);

    private static BlockState extractStateRule(SurfaceRule rule) {
        return isStateRule(rule) ? rule.tryApply(0, 0, 0) : null;
    }

    /**
     * Monomorphic, {@code final} condition evaluator that eliminates megamorphic
     * {@code SurfaceConditionAccess.chunking$test()} interface dispatch, unwraps
     * {@code NotCondition}, specializes {@code VerticalGradientCondition} and
     * {@code BiomeCondition} without {@code LazyCondition.test()} or {@code compute()} calls,
     * and routes remaining {@code LazyXZCondition}/{@code LazyYCondition} instances through
     * CHA-monomorphic {@link LazyFastAccess}.
     */
    static final class CompiledCond {
        final int tag;
        final boolean negate;
        final boolean isXZ;
        final SurfaceContextAccessor ctx;
        final SurfaceConditionAccess generic;
        final Object rawCond;
        final LazyFastAccess lazy;

        // COND_VERT_GRAD fields
        final int trueAtAndBelow;
        final int falseAtAndAbove;
        final PositionalRandomFactory randomFactory;
        final int rngKind; // 0 = fallback, 1 = Xoroshiro, 2 = Legacy
        final long seedLo;
        final long seedHi;

        // COND_BIOME fields (vanilla BiomeCondition extends LazyYCondition for 3D biomes)
        final HolderSet<Biome> biomes;
        long biomeLastY;
        boolean biomeCachedResult;
        Holder<Biome> lastBiome;
        boolean lastBiomeMatch;

        private CompiledCond(
                int tag,
                boolean negate,
                boolean isXZ,
                SurfaceContextAccessor ctx,
                SurfaceConditionAccess generic,
                Object rawCond,
                LazyFastAccess lazy,
                int trueAtAndBelow,
                int falseAtAndAbove,
                PositionalRandomFactory randomFactory,
                int rngKind,
                long seedLo,
                long seedHi,
                HolderSet<Biome> biomes
        ) {
            this.tag = tag;
            this.negate = negate;
            this.isXZ = isXZ;
            this.ctx = ctx;
            this.generic = generic;
            this.rawCond = rawCond;
            this.lazy = lazy;
            this.trueAtAndBelow = trueAtAndBelow;
            this.falseAtAndAbove = falseAtAndAbove;
            this.randomFactory = randomFactory;
            this.rngKind = rngKind;
            this.seedLo = seedLo;
            this.seedHi = seedHi;
            this.biomes = biomes;
            this.biomeLastY = (ctx != null) ? (ctx.chunking$getLastUpdateY() - 1L) : Long.MIN_VALUE;
        }

        static CompiledCond compile(SurfaceConditionAccess orig) {
            return compile((Object) orig);
        }

        static CompiledCond compile(Object orig) {
            if (orig == null) {
                return null;
            }
            boolean neg = false;
            Object cur = orig;
            while (cur != null
                    && NOT_COND_CLASS != null
                    && cur.getClass() == NOT_COND_CLASS
                    && NOT_COND_TARGET_GETTER != null) {
                try {
                    Object inner = NOT_COND_TARGET_GETTER.invokeExact(cur);
                    if (inner == null) {
                        break;
                    }
                    neg = !neg;
                    cur = inner;
                } catch (Throwable ignored) {
                    break;
                }
            }

            if (cur != null
                    && VERT_GRAD_COND_CLASS != null
                    && cur.getClass() == VERT_GRAD_COND_CLASS
                    && VERT_GRAD_TRUE_GETTER != null
                    && VERT_GRAD_FALSE_GETTER != null
                    && VERT_GRAD_RNG_GETTER != null
                    && cur instanceof LazyFastAccess lfa) {
                SurfaceContextAccessor ctx = lfa.chunking$getLazyContext();
                if (ctx != null) {
                    try {
                        int trueBelow = (int) VERT_GRAD_TRUE_GETTER.invokeExact(cur);
                        int falseAbove = (int) VERT_GRAD_FALSE_GETTER.invokeExact(cur);
                        PositionalRandomFactory rf = (PositionalRandomFactory) VERT_GRAD_RNG_GETTER.invokeExact(cur);
                        if (rf != null) {
                            int rKind = 0;
                            long sLo = 0L;
                            long sHi = 0L;
                            if (rf instanceof XoroshiroPositionalAccessor xoro) {
                                rKind = 1;
                                sLo = xoro.chunking$getSeedLo();
                                sHi = xoro.chunking$getSeedHi();
                            } else if (rf instanceof LegacyPositionalAccessor legacy) {
                                rKind = 2;
                                sLo = legacy.chunking$getSeed();
                            }
                            return new CompiledCond(
                                    COND_VERT_GRAD, neg, false, ctx, null, null, null,
                                    trueBelow, falseAbove, rf, rKind, sLo, sHi, null
                            );
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (cur != null
                    && BIOME_COND_CLASS != null
                    && cur.getClass() == BIOME_COND_CLASS
                    && cur instanceof LazyFastAccess lfa) {
                SurfaceContextAccessor ctx = lfa.chunking$getLazyContext();
                HolderSet<Biome> set = extractBiomeConditionBiomes(cur);
                if (ctx != null && set != null) {
                    return new CompiledCond(
                            COND_BIOME, neg, false, ctx, null, null, null,
                            0, 0, null, 0, 0L, 0L, set
                    );
                }
            }

            if (cur instanceof LazyFastAccess lfa) {
                int kind = lfa.chunking$getLazyKind();
                SurfaceContextAccessor ctx = lfa.chunking$getLazyContext();
                if (kind == 1 && ctx != null) {
                    return new CompiledCond(
                            COND_LAZY_XZ, neg, true, ctx, null, null, lfa,
                            0, 0, null, 0, 0L, 0L, null
                    );
                }
                if (kind == 2 && ctx != null) {
                    return new CompiledCond(
                            COND_LAZY_Y, neg, false, ctx, null, null, lfa,
                            0, 0, null, 0, 0L, 0L, null
                    );
                }
            }

            if (cur instanceof SurfaceConditionAccess acc) {
                return new CompiledCond(
                        COND_GENERIC, neg, false, null, acc, null, null,
                        0, 0, null, 0, 0L, 0L, null
                );
            }
            if (COND_TEST_MH != null && COND_CLASS != null && COND_CLASS.isInstance(cur)) {
                return new CompiledCond(
                        COND_RAW_COND, neg, false, null, null, cur, null,
                        0, 0, null, 0, 0L, 0L, null
                );
            }
            if (orig instanceof SurfaceConditionAccess acc) {
                return new CompiledCond(
                        COND_GENERIC, false, false, null, acc, null, null,
                        0, 0, null, 0, 0L, 0L, null
                );
            }
            if (COND_TEST_MH != null && COND_CLASS != null && COND_CLASS.isInstance(orig)) {
                return new CompiledCond(
                        COND_RAW_COND, false, false, null, null, orig, null,
                        0, 0, null, 0, 0L, 0L, null
                );
            }
            return null;
        }

        boolean eval(long curXZ, long curY) {
            boolean res;
            switch (this.tag) {
                case COND_VERT_GRAD -> {
                    int y = this.ctx.chunking$getBlockY();
                    if (y <= this.trueAtAndBelow) {
                        res = true;
                    } else if (y >= this.falseAtAndAbove) {
                        res = false;
                    } else {
                        res = evalVerticalGradientBandFast(
                                this.ctx, y, this.trueAtAndBelow, this.falseAtAndAbove,
                                this.randomFactory, this.rngKind, this.seedLo, this.seedHi
                        );
                    }
                }
                case COND_BIOME -> {
                    if (curY == this.biomeLastY) {
                        res = this.biomeCachedResult;
                    } else {
                        Holder<Biome> cur = this.ctx.chunking$getBiome();
                        if (cur != null && cur == this.lastBiome) {
                            res = this.lastBiomeMatch;
                        } else {
                            res = cur != null && this.biomes.contains(cur);
                            this.lastBiome = cur;
                            this.lastBiomeMatch = res;
                        }
                        this.biomeCachedResult = res;
                        this.biomeLastY = curY;
                    }
                }
                case COND_LAZY_XZ -> res = this.lazy.chunking$testXZ(curXZ);
                case COND_LAZY_Y -> res = this.lazy.chunking$testY(curY);
                case COND_RAW_COND -> res = invokeRawCondition(this.rawCond);
                default -> res = this.generic.chunking$test();
            }
            return res ^ this.negate;
        }

        boolean evalStandalone() {
            boolean res;
            switch (this.tag) {
                case COND_VERT_GRAD -> {
                    int y = this.ctx.chunking$getBlockY();
                    if (y <= this.trueAtAndBelow) {
                        res = true;
                    } else if (y >= this.falseAtAndAbove) {
                        res = false;
                    } else {
                        res = evalVerticalGradientBandFast(
                                this.ctx, y, this.trueAtAndBelow, this.falseAtAndAbove,
                                this.randomFactory, this.rngKind, this.seedLo, this.seedHi
                        );
                    }
                }
                case COND_BIOME -> {
                    long curY = this.ctx.chunking$getLastUpdateY();
                    if (curY == this.biomeLastY) {
                        res = this.biomeCachedResult;
                    } else {
                        Holder<Biome> cur = this.ctx.chunking$getBiome();
                        if (cur != null && cur == this.lastBiome) {
                            res = this.lastBiomeMatch;
                        } else {
                            res = cur != null && this.biomes.contains(cur);
                            this.lastBiome = cur;
                            this.lastBiomeMatch = res;
                        }
                        this.biomeCachedResult = res;
                        this.biomeLastY = curY;
                    }
                }
                case COND_LAZY_XZ -> res = this.lazy.chunking$testXZ(this.ctx.chunking$getLastUpdateXZ());
                case COND_LAZY_Y -> res = this.lazy.chunking$testY(this.ctx.chunking$getLastUpdateY());
                case COND_RAW_COND -> res = invokeRawCondition(this.rawCond);
                default -> res = this.generic.chunking$test();
            }
            return res ^ this.negate;
        }
    }

    public static final class FastTestRule implements SurfaceRule {
        final SurfaceConditionAccess cond;
        final CompiledCond compiledCond;
        final SurfaceRule followup;
        final BlockState directState;

        public FastTestRule(SurfaceConditionAccess cond, SurfaceRule followup) {
            this(cond, CompiledCond.compile(cond), followup);
        }

        FastTestRule(SurfaceConditionAccess cond, CompiledCond compiledCond, SurfaceRule followup) {
            this.cond = cond;
            this.compiledCond = compiledCond;
            this.followup = followup;
            this.directState = extractStateRule(followup);
        }

        public SurfaceConditionAccess cond() {
            return this.cond;
        }

        public SurfaceRule followup() {
            return this.followup;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (!this.compiledCond.evalStandalone()) {
                return null;
            }
            return this.directState != null ? this.directState : this.followup.tryApply(x, y, z);
        }
    }

    public static final class FastAnd2Rule implements SurfaceRule {
        final CompiledCond cond1;
        final CompiledCond cond2;
        final SurfaceRule followup;
        final BlockState directState;

        public FastAnd2Rule(
                SurfaceConditionAccess cond1,
                SurfaceConditionAccess cond2,
                SurfaceRule followup
        ) {
            this(CompiledCond.compile(cond1), CompiledCond.compile(cond2), followup);
        }

        FastAnd2Rule(CompiledCond cond1, CompiledCond cond2, SurfaceRule followup) {
            this.cond1 = cond1;
            this.cond2 = cond2;
            this.followup = followup;
            this.directState = extractStateRule(followup);
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (!(this.cond1.evalStandalone() && this.cond2.evalStandalone())) {
                return null;
            }
            return this.directState != null ? this.directState : this.followup.tryApply(x, y, z);
        }
    }

    public static final class FastAnd3Rule implements SurfaceRule {
        final CompiledCond cond1;
        final CompiledCond cond2;
        final CompiledCond cond3;
        final SurfaceRule followup;
        final BlockState directState;

        public FastAnd3Rule(
                SurfaceConditionAccess cond1,
                SurfaceConditionAccess cond2,
                SurfaceConditionAccess cond3,
                SurfaceRule followup
        ) {
            this(CompiledCond.compile(cond1), CompiledCond.compile(cond2), CompiledCond.compile(cond3), followup);
        }

        FastAnd3Rule(CompiledCond cond1, CompiledCond cond2, CompiledCond cond3, SurfaceRule followup) {
            this.cond1 = cond1;
            this.cond2 = cond2;
            this.cond3 = cond3;
            this.followup = followup;
            this.directState = extractStateRule(followup);
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (!(this.cond1.evalStandalone()
                    && this.cond2.evalStandalone()
                    && this.cond3.evalStandalone())) {
                return null;
            }
            return this.directState != null ? this.directState : this.followup.tryApply(x, y, z);
        }
    }

    public static final class FastAnd4Rule implements SurfaceRule {
        final CompiledCond cond1;
        final CompiledCond cond2;
        final CompiledCond cond3;
        final CompiledCond cond4;
        final SurfaceRule followup;
        final BlockState directState;

        public FastAnd4Rule(
                SurfaceConditionAccess cond1,
                SurfaceConditionAccess cond2,
                SurfaceConditionAccess cond3,
                SurfaceConditionAccess cond4,
                SurfaceRule followup
        ) {
            this(
                    CompiledCond.compile(cond1),
                    CompiledCond.compile(cond2),
                    CompiledCond.compile(cond3),
                    CompiledCond.compile(cond4),
                    followup
            );
        }

        FastAnd4Rule(
                CompiledCond cond1,
                CompiledCond cond2,
                CompiledCond cond3,
                CompiledCond cond4,
                SurfaceRule followup
        ) {
            this.cond1 = cond1;
            this.cond2 = cond2;
            this.cond3 = cond3;
            this.cond4 = cond4;
            this.followup = followup;
            this.directState = extractStateRule(followup);
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (!(this.cond1.evalStandalone()
                    && this.cond2.evalStandalone()
                    && this.cond3.evalStandalone()
                    && this.cond4.evalStandalone())) {
                return null;
            }
            return this.directState != null ? this.directState : this.followup.tryApply(x, y, z);
        }
    }

    public static final class FlatSequenceRule implements SurfaceRule {
        final SurfaceRule[] rules;
        final CompiledCond[] conds1;
        final CompiledCond[] conds2;
        final CompiledCond[] conds3;
        final CompiledCond[] conds4;
        final BlockState[] directStates;
        final SurfaceRule[] thens;
        final SurfaceContextAccessor ctx;
        long lastDeadMaskXZ;
        long deadMask;

        FlatSequenceRule(SurfaceRule[] rules) {
            this(rules, null);
        }

        FlatSequenceRule(SurfaceRule[] rules, SurfaceContextAccessor ctx) {
            this.rules = rules;
            int len = rules.length;
            CompiledCond[] c1 = new CompiledCond[len];
            CompiledCond[] c2 = new CompiledCond[len];
            CompiledCond[] c3 = new CompiledCond[len];
            CompiledCond[] c4 = new CompiledCond[len];
            BlockState[] ds = new BlockState[len];
            SurfaceRule[] th = new SurfaceRule[len];
            SurfaceContextAccessor resolvedCtx = ctx;
            for (int i = 0; i < len; i++) {
                SurfaceRule r = rules[i];
                if (r instanceof FastTestRule ft) {
                    c1[i] = ft.compiledCond;
                    if (resolvedCtx == null && ft.compiledCond.ctx != null) {
                        resolvedCtx = ft.compiledCond.ctx;
                    }
                    ds[i] = ft.directState;
                    th[i] = ft.followup;
                } else if (r instanceof FastAnd2Rule fa2) {
                    c1[i] = fa2.cond1;
                    c2[i] = fa2.cond2;
                    if (resolvedCtx == null) {
                        resolvedCtx = fa2.cond1.ctx != null ? fa2.cond1.ctx : fa2.cond2.ctx;
                    }
                    ds[i] = fa2.directState;
                    th[i] = fa2.followup;
                } else if (r instanceof FastAnd3Rule fa3) {
                    c1[i] = fa3.cond1;
                    c2[i] = fa3.cond2;
                    c3[i] = fa3.cond3;
                    if (resolvedCtx == null) {
                        resolvedCtx = fa3.cond1.ctx != null ? fa3.cond1.ctx
                                : (fa3.cond2.ctx != null ? fa3.cond2.ctx : fa3.cond3.ctx);
                    }
                    ds[i] = fa3.directState;
                    th[i] = fa3.followup;
                } else if (r instanceof FastAnd4Rule fa4) {
                    c1[i] = fa4.cond1;
                    c2[i] = fa4.cond2;
                    c3[i] = fa4.cond3;
                    c4[i] = fa4.cond4;
                    if (resolvedCtx == null) {
                        resolvedCtx = fa4.cond1.ctx != null ? fa4.cond1.ctx
                                : (fa4.cond2.ctx != null ? fa4.cond2.ctx
                                : (fa4.cond3.ctx != null ? fa4.cond3.ctx : fa4.cond4.ctx));
                    }
                    ds[i] = fa4.directState;
                    th[i] = fa4.followup;
                } else {
                    ds[i] = extractStateRule(r);
                    th[i] = r;
                }
            }
            this.conds1 = c1;
            this.conds2 = c2;
            this.conds3 = c3;
            this.conds4 = c4;
            this.directStates = ds;
            this.thens = th;
            this.ctx = resolvedCtx;
            this.lastDeadMaskXZ = (resolvedCtx != null) ? (resolvedCtx.chunking$getLastUpdateXZ() - 1L) : Long.MIN_VALUE;
            this.deadMask = 0L;
        }

        public SurfaceRule[] rules() {
            return this.rules;
        }

        public int ruleCount() {
            return this.rules.length;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            SurfaceContextAccessor ctxAcc = this.ctx;
            long curXZ = 0L;
            long curY = 0L;
            long dead = 0L;
            if (ctxAcc != null) {
                curXZ = ctxAcc.chunking$getLastUpdateXZ();
                curY = ctxAcc.chunking$getLastUpdateY();
                if (curXZ != this.lastDeadMaskXZ) {
                    this.lastDeadMaskXZ = curXZ;
                    this.deadMask = 0L;
                } else {
                    dead = this.deadMask;
                }
            }
            CompiledCond[] c1s = this.conds1;
            CompiledCond[] c2s = this.conds2;
            CompiledCond[] c3s = this.conds3;
            CompiledCond[] c4s = this.conds4;
            BlockState[] ds = this.directStates;
            SurfaceRule[] ts = this.thens;
            for (int i = 0, len = ts.length; i < len; i++) {
                if (((dead >>> i) & 1L) != 0L) {
                    continue;
                }
                CompiledCond c1 = c1s[i];
                if (c1 != null) {
                    if (!c1.eval(curXZ, curY)) {
                        if (c1.isXZ) {
                            dead |= (1L << i);
                            this.deadMask = dead;
                        }
                        continue;
                    }
                    CompiledCond c2 = c2s[i];
                    if (c2 != null) {
                        if (!c2.eval(curXZ, curY)) {
                            if (c2.isXZ) {
                                dead |= (1L << i);
                                this.deadMask = dead;
                            }
                            continue;
                        }
                        CompiledCond c3 = c3s[i];
                        if (c3 != null) {
                            if (!c3.eval(curXZ, curY)) {
                                if (c3.isXZ) {
                                    dead |= (1L << i);
                                    this.deadMask = dead;
                                }
                                continue;
                            }
                            CompiledCond c4 = c4s[i];
                            if (c4 != null && !c4.eval(curXZ, curY)) {
                                if (c4.isXZ) {
                                    dead |= (1L << i);
                                    this.deadMask = dead;
                                }
                                continue;
                            }
                        }
                    }
                }
                BlockState direct = ds[i];
                if (direct != null) {
                    return direct;
                }
                BlockState state = ts[i].tryApply(x, y, z);
                if (state != null) {
                    return state;
                }
            }
            return null;
        }
    }

    /**
     * Multi-word XZ dead-rule bitmask sequence used only when {@code rules.length > 64}
     * so vanilla and Terralith ({@code <= 64}) keep {@link FlatSequenceRule}'s single-long fast path.
     */
    public static final class LargeFlatSequenceRule implements SurfaceRule {
        final SurfaceRule[] rules;
        final CompiledCond[] conds1;
        final CompiledCond[] conds2;
        final CompiledCond[] conds3;
        final CompiledCond[] conds4;
        final BlockState[] directStates;
        final SurfaceRule[] thens;
        final SurfaceContextAccessor ctx;
        final long[] deadWords;
        long lastDeadMaskXZ;

        LargeFlatSequenceRule(SurfaceRule[] rules, SurfaceContextAccessor ctx) {
            this.rules = rules;
            int len = rules.length;
            CompiledCond[] c1 = new CompiledCond[len];
            CompiledCond[] c2 = new CompiledCond[len];
            CompiledCond[] c3 = new CompiledCond[len];
            CompiledCond[] c4 = new CompiledCond[len];
            BlockState[] ds = new BlockState[len];
            SurfaceRule[] th = new SurfaceRule[len];
            SurfaceContextAccessor resolvedCtx = ctx;
            for (int i = 0; i < len; i++) {
                SurfaceRule r = rules[i];
                if (r instanceof FastTestRule ft) {
                    c1[i] = ft.compiledCond;
                    if (resolvedCtx == null && ft.compiledCond.ctx != null) {
                        resolvedCtx = ft.compiledCond.ctx;
                    }
                    ds[i] = ft.directState;
                    th[i] = ft.followup;
                } else if (r instanceof FastAnd2Rule fa2) {
                    c1[i] = fa2.cond1;
                    c2[i] = fa2.cond2;
                    if (resolvedCtx == null) {
                        resolvedCtx = fa2.cond1.ctx != null ? fa2.cond1.ctx : fa2.cond2.ctx;
                    }
                    ds[i] = fa2.directState;
                    th[i] = fa2.followup;
                } else if (r instanceof FastAnd3Rule fa3) {
                    c1[i] = fa3.cond1;
                    c2[i] = fa3.cond2;
                    c3[i] = fa3.cond3;
                    if (resolvedCtx == null) {
                        resolvedCtx = fa3.cond1.ctx != null ? fa3.cond1.ctx
                                : (fa3.cond2.ctx != null ? fa3.cond2.ctx : fa3.cond3.ctx);
                    }
                    ds[i] = fa3.directState;
                    th[i] = fa3.followup;
                } else if (r instanceof FastAnd4Rule fa4) {
                    c1[i] = fa4.cond1;
                    c2[i] = fa4.cond2;
                    c3[i] = fa4.cond3;
                    c4[i] = fa4.cond4;
                    if (resolvedCtx == null) {
                        resolvedCtx = fa4.cond1.ctx != null ? fa4.cond1.ctx
                                : (fa4.cond2.ctx != null ? fa4.cond2.ctx
                                : (fa4.cond3.ctx != null ? fa4.cond3.ctx : fa4.cond4.ctx));
                    }
                    ds[i] = fa4.directState;
                    th[i] = fa4.followup;
                } else {
                    ds[i] = extractStateRule(r);
                    th[i] = r;
                }
            }
            this.conds1 = c1;
            this.conds2 = c2;
            this.conds3 = c3;
            this.conds4 = c4;
            this.directStates = ds;
            this.thens = th;
            this.ctx = resolvedCtx;
            this.deadWords = new long[(len + 63) >>> 6];
            this.lastDeadMaskXZ = (resolvedCtx != null) ? (resolvedCtx.chunking$getLastUpdateXZ() - 1L) : Long.MIN_VALUE;
        }

        public SurfaceRule[] rules() {
            return this.rules;
        }

        public int ruleCount() {
            return this.rules.length;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            SurfaceContextAccessor ctxAcc = this.ctx;
            long curXZ = 0L;
            long curY = 0L;
            long[] dw = this.deadWords;
            if (ctxAcc != null) {
                curXZ = ctxAcc.chunking$getLastUpdateXZ();
                curY = ctxAcc.chunking$getLastUpdateY();
                if (curXZ != this.lastDeadMaskXZ) {
                    this.lastDeadMaskXZ = curXZ;
                    Arrays.fill(dw, 0L);
                }
            }
            CompiledCond[] c1s = this.conds1;
            CompiledCond[] c2s = this.conds2;
            CompiledCond[] c3s = this.conds3;
            CompiledCond[] c4s = this.conds4;
            BlockState[] ds = this.directStates;
            SurfaceRule[] ts = this.thens;
            for (int i = 0, len = ts.length; i < len; i++) {
                int wi = i >>> 6;
                long bit = 1L << i;
                if ((dw[wi] & bit) != 0L) {
                    continue;
                }
                CompiledCond c1 = c1s[i];
                if (c1 != null) {
                    if (!c1.eval(curXZ, curY)) {
                        if (c1.isXZ) {
                            dw[wi] |= bit;
                        }
                        continue;
                    }
                    CompiledCond c2 = c2s[i];
                    if (c2 != null) {
                        if (!c2.eval(curXZ, curY)) {
                            if (c2.isXZ) {
                                dw[wi] |= bit;
                            }
                            continue;
                        }
                        CompiledCond c3 = c3s[i];
                        if (c3 != null) {
                            if (!c3.eval(curXZ, curY)) {
                                if (c3.isXZ) {
                                    dw[wi] |= bit;
                                }
                                continue;
                            }
                            CompiledCond c4 = c4s[i];
                            if (c4 != null && !c4.eval(curXZ, curY)) {
                                if (c4.isXZ) {
                                    dw[wi] |= bit;
                                }
                                continue;
                            }
                        }
                    }
                }
                BlockState direct = ds[i];
                if (direct != null) {
                    return direct;
                }
                BlockState state = ts[i].tryApply(x, y, z);
                if (state != null) {
                    return state;
                }
            }
            return null;
        }
    }

    public static BlockState tryApplyRule(Object rule, int x, int y, int z) {
        return ((SurfaceRule) rule).tryApply(x, y, z);
    }

    public static RuleSource dummyNilRuleSource() {
        return new RuleSource() {
            @Override
            public com.mojang.serialization.MapCodec<? extends RuleSource> codec() {
                throw new UnsupportedOperationException();
            }

            @Override
            public SurfaceRule apply(Context ctx) {
                return NEVER_MATCH_RULE;
            }
        };
    }

    public static RuleSource constRuleSource(Object ruleObj) {
        final SurfaceRule target = (SurfaceRule) ruleObj;
        return new RuleSource() {
            @Override
            public com.mojang.serialization.MapCodec<? extends RuleSource> codec() {
                throw new UnsupportedOperationException();
            }

            @Override
            public SurfaceRule apply(Context ctx) {
                return target;
            }
        };
    }

    public static RuleSource dummyUnmatchedRuleSource() {
        final SurfaceRule unmatched = (x, y, z) -> null;
        return new RuleSource() {
            @Override
            public com.mojang.serialization.MapCodec<? extends RuleSource> codec() {
                throw new UnsupportedOperationException();
            }

            @Override
            public SurfaceRule apply(Context ctx) {
                return unmatched;
            }
        };
    }

    private FastSurface() {}

    private static MethodHandle resolveCondTestHandle() {
        if (COND_CLASS == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(SurfaceRules.class, MethodHandles.lookup());
            return lookup.findVirtual(COND_CLASS, "test", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeRawCondition(Object cond) {
        try {
            return (boolean) COND_TEST_MH.invokeExact(cond);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed invoking SurfaceRules.Condition.test()", t);
        }
    }

    private static MethodHandle resolveZeroArgGetter(Class<?> cls, String name, Class<?> retType) {
        if (cls == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
            return lookup.findVirtual(cls, name, MethodType.methodType(retType))
                    .asType(MethodType.methodType(retType, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveListCtor(String className) {
        Class<?> cls = resolveClass(className);
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            return MethodHandles.lookup().unreflectConstructor(ctor)
                    .asType(MethodType.methodType(Object.class, List.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> resolveClass(String name) {
        try {
            return Class.forName(name, false, SurfaceRules.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveLazyContextGetter() {
        try {
            Class<?> lazyClass = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$LazyCondition",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(lazyClass, MethodHandles.lookup());
            Field ctxField = lazyClass.getDeclaredField("context");
            return lookup.unreflectGetter(ctxField).asType(MethodType.methodType(Object.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveBiomeCondThis0Getter() {
        try {
            Class<?> c = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().startsWith("this$") || f.getType().getName().endsWith("$BiomeConditionSource")) {
                    return lookup.unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static MethodHandle resolveBiomeSrcBiomesGetter() {
        try {
            Class<?> c = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
            Field f = c.getDeclaredField("biomes");
            return lookup.unreflectGetter(f).asType(MethodType.methodType(HolderSet.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveNotCondTargetGetter() {
        if (NOT_COND_CLASS == null || COND_CLASS == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(NOT_COND_CLASS, MethodHandles.lookup());
            for (Field f : NOT_COND_CLASS.getDeclaredFields()) {
                if (f.getType() == COND_CLASS) {
                    return lookup.unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static MethodHandle resolveVertGradIntGetter(String fieldName) {
        if (VERT_GRAD_COND_CLASS == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(VERT_GRAD_COND_CLASS, MethodHandles.lookup());
            Field f = VERT_GRAD_COND_CLASS.getDeclaredField(fieldName);
            return lookup.unreflectGetter(f).asType(MethodType.methodType(int.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveVertGradRngGetter() {
        if (VERT_GRAD_COND_CLASS == null) {
            return null;
        }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(VERT_GRAD_COND_CLASS, MethodHandles.lookup());
            Field f = VERT_GRAD_COND_CLASS.getDeclaredField("val$randomFactory");
            return lookup.unreflectGetter(f).asType(MethodType.methodType(PositionalRandomFactory.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveTestRuleCtor() {
        try {
            Class<?> condClass = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$Condition",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            Class<?> testClass = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$TestRule",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            Constructor<?> ctor = testClass.getDeclaredConstructor(condClass, SurfaceRule.class);
            ctor.setAccessible(true);
            return MethodHandles.lookup().unreflectConstructor(ctor)
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle resolveNotCondCtor() {
        try {
            Class<?> condClass = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$Condition",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            Class<?> notClass = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$NotCondition",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            Constructor<?> ctor = notClass.getDeclaredConstructor(condClass);
            ctor.setAccessible(true);
            return MethodHandles.lookup().unreflectConstructor(ctor)
                    .asType(MethodType.methodType(Object.class, Object.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean alwaysFalseImpl() {
        return false;
    }

    public static boolean alwaysTrueImpl() {
        return true;
    }

    private static Object synthesizeConstCondition(boolean value) {
        try {
            Class<?> condClass = Class.forName(
                    "net.minecraft.world.level.levelgen.SurfaceRules$Condition",
                    false,
                    SurfaceRules.class.getClassLoader()
            );
            MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(SurfaceRules.class, MethodHandles.lookup());
            MethodHandle impl = privateLookup.findStatic(
                    FastSurface.class,
                    value ? "alwaysTrueImpl" : "alwaysFalseImpl",
                    MethodType.methodType(boolean.class)
            );
            CallSite cs = LambdaMetafactory.metafactory(
                    privateLookup,
                    "test",
                    MethodType.methodType(condClass),
                    MethodType.methodType(boolean.class),
                    impl,
                    MethodType.methodType(boolean.class)
            );
            return cs.getTarget().invoke();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object registerAlwaysFalse(Object cond) {
        Object cur = alwaysFalseCondition;
        if (cur == null && cond != null) {
            alwaysFalseCondition = cond;
            return cond;
        }
        return cur != null ? cur : cond;
    }

    public static Object registerAlwaysTrue(Object cond) {
        Object cur = alwaysTrueCondition;
        if (cur == null && cond != null) {
            alwaysTrueCondition = cond;
            return cond;
        }
        return cur != null ? cur : cond;
    }

    public static Object alwaysFalse() {
        return alwaysFalseCondition;
    }

    public static Object alwaysTrue() {
        return alwaysTrueCondition;
    }

    public static boolean isAlwaysFalse(Object cond) {
        Object f = alwaysFalseCondition;
        return f != null && cond == f;
    }

    public static boolean isAlwaysTrue(Object cond) {
        Object t = alwaysTrueCondition;
        return t != null && cond == t;
    }

    public static Object foldNotCondition(Object inner) {
        if (inner == null) {
            return null;
        }
        if (isAlwaysFalse(inner)) {
            return alwaysTrueCondition;
        }
        if (isAlwaysTrue(inner)) {
            return alwaysFalseCondition;
        }
        return null;
    }

    public static boolean isNeverMatch(Object rule) {
        return rule == NEVER_MATCH_RULE;
    }

    public static boolean isStateRule(Object rule) {
        return rule != null && STATE_RULE_CLASS != null && rule.getClass() == STATE_RULE_CLASS;
    }

    public static int classifyLazyCondition(Object cond) {
        if (LAZY_XZ_CLASS != null && LAZY_XZ_CLASS.isInstance(cond)) {
            return 1;
        }
        if (LAZY_Y_CLASS != null && LAZY_Y_CLASS.isInstance(cond)) {
            return 2;
        }
        return 0;
    }

    public static SurfaceContextAccessor getLazyContextAccessor(Object lazyConditionSelf) {
        try {
            Object ctxObj = LAZY_CONTEXT_GETTER.invokeExact(lazyConditionSelf);
            return (SurfaceContextAccessor) ctxObj;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to read SurfaceRules.LazyCondition.context", t);
        }
    }

    public static boolean canBuildTestRule() {
        return TEST_RULE_CTOR != null;
    }

    public static boolean canCompileCondition() {
        return COND_TEST_MH != null;
    }

    public static Object newTestRule(Object cond, Object followup) {
        if (followup instanceof SurfaceRule rule) {
            CompiledCond compiled = CompiledCond.compile(cond);
            if (compiled != null) {
                if (rule instanceof FastTestRule ft) {
                    return new FastAnd2Rule(compiled, ft.compiledCond, ft.followup);
                }
                if (rule instanceof FastAnd2Rule fa2) {
                    return new FastAnd3Rule(compiled, fa2.cond1, fa2.cond2, fa2.followup);
                }
                if (rule instanceof FastAnd3Rule fa3) {
                    return new FastAnd4Rule(compiled, fa3.cond1, fa3.cond2, fa3.cond3, fa3.followup);
                }
                SurfaceConditionAccess cAcc = (cond instanceof SurfaceConditionAccess acc) ? acc : null;
                return new FastTestRule(cAcc, compiled, rule);
            }
        }
        try {
            return TEST_RULE_CTOR.invokeExact(cond, followup);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to construct SurfaceRules.TestRule", t);
        }
    }

    public static boolean canBuildNotCondition() {
        return NOT_COND_CTOR != null;
    }

    public static Object newNotCondition(Object inner) {
        try {
            return NOT_COND_CTOR.invokeExact(inner);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to construct SurfaceRules.NotCondition", t);
        }
    }

    public static Object evalRuleSource(RuleSource source, Object ctxObj) {
        return source.apply((Context) ctxObj);
    }

    @SuppressWarnings("unchecked")
    public static Object evalConditionSource(ConditionSource source, Object ctxObj) {
        if (LITHO_BIOME_SRC_CLASS != null) {
            Class<?> cls = source.getClass();
            Object af = alwaysFalseCondition;
            Object at = alwaysTrueCondition;
            if (af != null && at != null) {
                if (cls == LITHO_BIOME_SRC_CLASS && LITHO_BIOME_GETTER != null
                        && ctxObj instanceof SurfaceContextAccessor ctxAcc) {
                    Set<Holder<Biome>> possible = ctxAcc.chunking$getPossibleBiomes();
                    if (possible != null) {
                        try {
                            HolderSet<Biome> biomes = (HolderSet<Biome>) LITHO_BIOME_GETTER.invokeExact((Object) source);
                            boolean anyMatch = false;
                            boolean allMatch = true;
                            for (Holder<Biome> b : possible) {
                                if (biomes.contains(b)) {
                                    anyMatch = true;
                                } else {
                                    allMatch = false;
                                }
                                if (anyMatch && !allMatch) {
                                    break;
                                }
                            }
                            if (!anyMatch) {
                                return af;
                            }
                            if (allMatch) {
                                return at;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (cls == LITHO_ALL_OF_SRC_CLASS && LITHO_ALL_OF_GETTER != null) {
                    try {
                        List<ConditionSource> list = (List<ConditionSource>) LITHO_ALL_OF_GETTER.invokeExact((Object) source);
                        Object first = null;
                        ArrayList<Object> kept = null;
                        for (int i = 0, n = list.size(); i < n; i++) {
                            Object c = evalConditionSource(list.get(i), ctxObj);
                            if (c == af) {
                                return af;
                            }
                            if (c == at) {
                                continue;
                            }
                            if (first == null) {
                                first = c;
                            } else {
                                if (kept == null) {
                                    kept = new ArrayList<>(n - i + 1);
                                    kept.add(first);
                                }
                                kept.add(c);
                            }
                        }
                        if (first == null) {
                            return at;
                        }
                        if (kept == null) {
                            return first;
                        }
                        if (LITHO_ALL_OF_COND_CTOR != null) {
                            return LITHO_ALL_OF_COND_CTOR.invokeExact((List<?>) kept);
                        }
                    } catch (Throwable ignored) {
                    }
                } else if (cls == LITHO_ANY_OF_SRC_CLASS && LITHO_ANY_OF_GETTER != null) {
                    try {
                        List<ConditionSource> list = (List<ConditionSource>) LITHO_ANY_OF_GETTER.invokeExact((Object) source);
                        Object first = null;
                        ArrayList<Object> kept = null;
                        for (int i = 0, n = list.size(); i < n; i++) {
                            Object c = evalConditionSource(list.get(i), ctxObj);
                            if (c == at) {
                                return at;
                            }
                            if (c == af) {
                                continue;
                            }
                            if (first == null) {
                                first = c;
                            } else {
                                if (kept == null) {
                                    kept = new ArrayList<>(n - i + 1);
                                    kept.add(first);
                                }
                                kept.add(c);
                            }
                        }
                        if (first == null) {
                            return af;
                        }
                        if (kept == null) {
                            return first;
                        }
                        if (LITHO_ANY_OF_COND_CTOR != null) {
                            return LITHO_ANY_OF_COND_CTOR.invokeExact((List<?>) kept);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return ((Function<Object, ?>) (Object) source).apply(ctxObj);
    }

    public static Holder<Biome> getContextBiome(Object lazyConditionSelf) {
        if (lazyConditionSelf instanceof LazyFastAccess lfa) {
            SurfaceContextAccessor acc = lfa.chunking$getLazyContext();
            if (acc != null) {
                return acc.chunking$getBiome();
            }
        }
        try {
            Object ctxObj = LAZY_CONTEXT_GETTER.invokeExact(lazyConditionSelf);
            return ((SurfaceContextAccessor) ctxObj).chunking$getBiome();
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to read SurfaceRules.LazyCondition.context", t);
        }
    }

    @SuppressWarnings("unchecked")
    public static HolderSet<Biome> extractBiomeConditionBiomes(Object biomeCondition) {
        if (BIOME_COND_THIS0_GETTER == null || BIOME_SRC_BIOMES_GETTER == null) {
            return null;
        }
        try {
            Object src = BIOME_COND_THIS0_GETTER.invokeExact(biomeCondition);
            return (HolderSet<Biome>) BIOME_SRC_BIOMES_GETTER.invokeExact(src);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean canFastEvalVerticalGradient() {
        return LAZY_CONTEXT_GETTER != null;
    }

    public static Object[] toRuleArray(List<?> rules) {
        return rules.toArray(new SurfaceRule[0]);
    }

    public static Object buildFlattenedSequence(List<RuleSource> sources, Object ctxObj) {
        Context ctx = (Context) ctxObj;
        int n = sources.size();
        if (n == 0) {
            return NEVER_MATCH_RULE;
        }
        if (n == 1) {
            SurfaceRule single = (SurfaceRule) (Object) sources.get(0).apply(ctx);
            return single == null ? NEVER_MATCH_RULE : single;
        }
        ArrayList<SurfaceRule> flat = new ArrayList<>(n);
        outer:
        for (int i = 0; i < n; i++) {
            SurfaceRule child = (SurfaceRule) (Object) sources.get(i).apply(ctx);
            if (child == null || child == NEVER_MATCH_RULE) {
                continue;
            }
            if (child instanceof FlatSequenceRule fseq) {
                SurfaceRule[] sub = fseq.rules;
                for (int j = 0; j < sub.length; j++) {
                    SurfaceRule sr = sub[j];
                    if (sr == null || sr == NEVER_MATCH_RULE) {
                        continue;
                    }
                    flat.add(sr);
                    if (extractStateRule(sr) != null) {
                        break outer;
                    }
                }
            } else if (child instanceof LargeFlatSequenceRule lfseq) {
                SurfaceRule[] sub = lfseq.rules;
                for (int j = 0; j < sub.length; j++) {
                    SurfaceRule sr = sub[j];
                    if (sr == null || sr == NEVER_MATCH_RULE) {
                        continue;
                    }
                    flat.add(sr);
                    if (extractStateRule(sr) != null) {
                        break outer;
                    }
                }
            } else if (child instanceof SequenceRuleAccess seqAcc) {
                Object[] sub = seqAcc.chunking$flatRules();
                for (int j = 0; j < sub.length; j++) {
                    SurfaceRule sr = (SurfaceRule) sub[j];
                    if (sr == null || sr == NEVER_MATCH_RULE) {
                        continue;
                    }
                    flat.add(sr);
                    if (extractStateRule(sr) != null) {
                        break outer;
                    }
                }
            } else {
                flat.add(child);
                if (extractStateRule(child) != null) {
                    break;
                }
            }
        }
        int sz = flat.size();
        if (sz == 0) {
            return NEVER_MATCH_RULE;
        }
        if (sz == 1) {
            return flat.get(0);
        }
        SurfaceContextAccessor ctxAcc =
                (ctxObj instanceof SurfaceContextAccessor acc) ? acc : null;
        SurfaceRule[] arr = flat.toArray(new SurfaceRule[sz]);
        return sz <= 64 ? new FlatSequenceRule(arr, ctxAcc) : new LargeFlatSequenceRule(arr, ctxAcc);
    }

    public static Object compileRuleList(List<?> rules) {
        if (rules == null || rules.isEmpty()) {
            return NEVER_MATCH_RULE;
        }
        int n = rules.size();
        ArrayList<SurfaceRule> flat = new ArrayList<>(n);
        outer:
        for (int i = 0; i < n; i++) {
            SurfaceRule child = (SurfaceRule) rules.get(i);
            if (child == null || child == NEVER_MATCH_RULE) {
                continue;
            }
            if (child instanceof FlatSequenceRule fseq) {
                for (SurfaceRule sr : fseq.rules) {
                    if (sr == null || sr == NEVER_MATCH_RULE) continue;
                    flat.add(sr);
                    if (extractStateRule(sr) != null) break outer;
                }
            } else if (child instanceof LargeFlatSequenceRule lfseq) {
                for (SurfaceRule sr : lfseq.rules) {
                    if (sr == null || sr == NEVER_MATCH_RULE) continue;
                    flat.add(sr);
                    if (extractStateRule(sr) != null) break outer;
                }
            } else if (child instanceof SequenceRuleAccess seqAcc) {
                for (Object o : seqAcc.chunking$flatRules()) {
                    SurfaceRule sr = (SurfaceRule) o;
                    if (sr == null || sr == NEVER_MATCH_RULE) continue;
                    flat.add(sr);
                    if (extractStateRule(sr) != null) break outer;
                }
            } else {
                flat.add(child);
                if (extractStateRule(child) != null) break;
            }
        }
        int sz = flat.size();
        if (sz == 0) {
            return NEVER_MATCH_RULE;
        }
        if (sz == 1) {
            return flat.get(0);
        }
        SurfaceRule[] arr = flat.toArray(new SurfaceRule[sz]);
        return sz <= 64 ? new FlatSequenceRule(arr, null) : new LargeFlatSequenceRule(arr, null);
    }

    public static BlockState evalSequence(Object[] arrObj, int x, int y, int z) {
        SurfaceRule[] arr = (SurfaceRule[]) arrObj;
        for (int i = 0, len = arr.length; i < len; i++) {
            BlockState state = arr[i].tryApply(x, y, z);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    public static BlockState evalListFallback(List<?> rules, int x, int y, int z) {
        for (int i = 0, n = rules.size(); i < n; i++) {
            BlockState state = ((SurfaceRule) rules.get(i)).tryApply(x, y, z);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    public static boolean evalVerticalGradient(
            Object conditionSelf,
            int trueAtAndBelow,
            int falseAtAndAbove,
            PositionalRandomFactory randomFactory,
            boolean fastRandom
    ) {
        SurfaceContextAccessor acc;
        if (conditionSelf instanceof LazyFastAccess lfa && lfa.chunking$getLazyContext() != null) {
            acc = lfa.chunking$getLazyContext();
        } else {
            try {
                Object ctxObj = LAZY_CONTEXT_GETTER.invokeExact(conditionSelf);
                acc = (SurfaceContextAccessor) ctxObj;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to read SurfaceRules.LazyCondition.context", t);
            }
        }
        int y = acc.chunking$getBlockY();
        if (y <= trueAtAndBelow) {
            return true;
        }
        if (y >= falseAtAndAbove) {
            return false;
        }
        return evalVerticalGradientBand(acc, y, trueAtAndBelow, falseAtAndAbove, randomFactory, fastRandom);
    }

    private static boolean evalVerticalGradientBandFast(
            SurfaceContextAccessor acc,
            int y,
            int trueAtAndBelow,
            int falseAtAndAbove,
            PositionalRandomFactory randomFactory,
            int rngKind,
            long seedLo,
            long seedHi
    ) {
        int x = acc.chunking$getBlockX();
        int z = acc.chunking$getBlockZ();
        double chance = 1.0D - (double) (y - trueAtAndBelow) / (double) (falseAtAndAbove - trueAtAndBelow);
        if (rngKind == 1) {
            long lo = Mth.getSeed(x, y, z) ^ seedLo;
            long hi = seedHi;
            if ((lo | hi) == 0L) {
                lo = -7046029254386353131L;
                hi = 7640891576956012809L;
            }
            long bits = Long.rotateLeft(lo + hi, 17) + lo;
            float f = (float) (bits >>> 40) * 5.9604645E-8F;
            return (double) f < chance;
        }
        if (rngKind == 2) {
            long s = ((Mth.getSeed(x, y, z) ^ seedLo) ^ 25214903917L) & 281474976710655L;
            s = (s * 25214903917L + 11L) & 281474976710655L;
            float f = (float) ((int) (s >>> 24)) * 5.9604645E-8F;
            return (double) f < chance;
        }
        return (double) randomFactory.at(x, y, z).nextFloat() < chance;
    }

    private static boolean evalVerticalGradientBand(
            SurfaceContextAccessor acc,
            int y,
            int trueAtAndBelow,
            int falseAtAndAbove,
            PositionalRandomFactory randomFactory,
            boolean fastRandom
    ) {
        int x = acc.chunking$getBlockX();
        int z = acc.chunking$getBlockZ();
        double chance = 1.0D - (double) (y - trueAtAndBelow) / (double) (falseAtAndAbove - trueAtAndBelow);
        if (fastRandom && randomFactory instanceof XoroshiroPositionalAccessor xoro) {
            long lo = Mth.getSeed(x, y, z) ^ xoro.chunking$getSeedLo();
            long hi = xoro.chunking$getSeedHi();
            if ((lo | hi) == 0L) {
                lo = -7046029254386353131L;
                hi = 7640891576956012809L;
            }
            long bits = Long.rotateLeft(lo + hi, 17) + lo;
            float f = (float) (bits >>> 40) * 5.9604645E-8F;
            return (double) f < chance;
        }
        if (fastRandom && randomFactory instanceof LegacyPositionalAccessor legacy) {
            long s = ((Mth.getSeed(x, y, z) ^ legacy.chunking$getSeed()) ^ 25214903917L) & 281474976710655L;
            s = (s * 25214903917L + 11L) & 281474976710655L;
            float f = (float) ((int) (s >>> 24)) * 5.9604645E-8F;
            return (double) f < chance;
        }
        return (double) randomFactory.at(x, y, z).nextFloat() < chance;
    }
}
