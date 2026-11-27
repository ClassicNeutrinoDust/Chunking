package com.chunking.engine.simd;

import com.chunking.engine.ChunkingMod;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.MapMaker;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.chunking.engine.access.BeardifierAccess;
import com.chunking.engine.access.Cache2DAccess;
import com.chunking.engine.access.FlatCacheAccess;
import com.chunking.engine.world.ModCompat;
import com.chunking.engine.access.NoiseInterpolatorAccess;

/**
 * SteelMC-inspired Java 25 {@link ClassFile} DAG JIT compiler for Minecraft {@link DensityFunction}s.
 *
 * <p>Unlike an interpreted opcode VM, this compiler lowers any vanilla or chunk-bound {@code DensityFunction}
 * DAG directly to hidden native-JIT JVM bytecode with:
 * <ul>
 *   <li>True DAG common subexpression elimination (CSE) into JVM {@code double} locals (CPU XMM registers)</li>
 *   <li>Zero subtree duplication for {@code CacheOnce} and shared diamond nodes</li>
 *   <li>Vertical-loop hoisting for 2D / {@code Cache2D} / {@code FlatCache} expressions on Y-slices</li>
 *   <li>Pre-hoisted {@code NoiseInterpolator} 8-corner trilinear evaluation inside cell fills</li>
 *   <li>Flattened allocation-free {@code CubicSpline} evaluation and graceful fallback for unknown mod nodes</li>
 * </ul>
 */
public final class NoiseJit {
    private static final int INLINE_SPLINE_NODE_LIMIT = 18;
    private static final int MAX_COL_SLICE_SLOTS = 32;
    private static final ClassFile CLASS_FILE = ClassFile.of();
    private static final AtomicInteger CLASS_SEQ = new AtomicInteger(1);

    public static final int PLAN_FLAT_CACHE = 0;
    public static final int PLAN_INTERP_SLICE = 1;
    public static final int PLAN_CELL_FILLER = 2;

    private static final Cache<TemplateKey, CompiledTemplate> TEMPLATE_CACHE = CacheBuilder.newBuilder()
            .maximumSize(256)
            .build();

    private static final Cache<IdentityKey, BoundKernel> STATELESS_CACHE = CacheBuilder.newBuilder()
            .maximumSize(256)
            .build();

    private static final CompiledTemplate FAILED_TEMPLATE = new CompiledTemplate(null);
    private static final java.util.Set<String> LOGGED_JIT_FAILURES = ConcurrentHashMap.newKeySet();

    private static final ConcurrentMap<Object, NoiseChunkPlan> ROUTER_PLANS =
            new MapMaker().weakKeys().makeMap();

    public static NoiseChunkPlan getOrCreatePlan(NoiseRouter initRouter) {
        NoiseChunkPlan plan = ROUTER_PLANS.get(initRouter);
        if (plan == null) {
            plan = ROUTER_PLANS.computeIfAbsent(initRouter, k -> new NoiseChunkPlan());
        }
        return plan;
    }

    private static final class IdentityKey {
        final DensityFunction fn;
        final int hash;

        IdentityKey(DensityFunction fn) {
            this.fn = fn;
            this.hash = System.identityHashCode(fn);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IdentityKey other && this.fn == other.fn;
        }
    }

    /**
     * Per-{@link NoiseRouter} compiled slot plan eliminating repeated {@link GraphBuilder} AST
     * walks and template lookups across chunks sharing identical topology.
     */
    public static final class NoiseChunkPlan {
        private static final byte SRC_FLAT = 0;
        private static final byte SRC_CACHE2D = 1;
        private static final byte SRC_INTERP = 2;
        private static final byte SRC_BEARDIFIER = 3;

        private record CompiledRecipe(
                JitSupport.Kernel kernel,
                Object[] templateBindings,
                boolean usesInterpolator,
                byte[] dynSrcKind,
                int[] dynDestIdx,
                int[] dynSrcIdx,
                int minFlats,
                int minCache2Ds,
                int minInterps,
                Class<?> fillerClass,
                BoundKernel staticKernel,
                BoundKernel emptyBeardifierKernel
        ) {}

        private static final CompiledRecipe UNCACHEABLE =
                new CompiledRecipe(null, null, false, null, null, null, 0, 0, 0, null, null, null);
        private static final CompiledRecipe DISABLED =
                new CompiledRecipe(null, null, false, null, null, null, 0, 0, 0, null, null, null);

        private final ConcurrentHashMap<Long, CompiledRecipe> slots = new ConcurrentHashMap<>(64);
        private final ConcurrentHashMap<Long, Object[]> pendingBindings = new ConcurrentHashMap<>(16);

        public BoundKernel resolve(
                int kind,
                int ord,
                DensityFunction fn,
                List<Object> flatCaches,
                List<Object> cache2Ds,
                List<?> interpolators
        ) {
            return resolve(kind, ord, fn, flatCaches, cache2Ds, interpolators, Beardifier.EMPTY);
        }

        public BoundKernel resolve(
                int kind,
                int ord,
                DensityFunction fn,
                List<Object> flatCaches,
                List<Object> cache2Ds,
                List<?> interpolators,
                Object beardifier
        ) {
            if (fn == null) {
                return null;
            }
            boolean beardEmpty = isEmptyBeardifier(beardifier);
            long key = ((long) kind << 33) | ((beardEmpty ? 1L : 0L) << 32) | (ord & 0xFFFFFFFFL);
            CompiledRecipe r = this.slots.get(key);
            if (r != null) {
                if (r == DISABLED) {
                    return null;
                }
                if (r == UNCACHEABLE) {
                    return compileAndBind(fn);
                }
                if (r.fillerClass != fn.getClass()
                        || flatCaches.size() < r.minFlats
                        || cache2Ds.size() < r.minCache2Ds
                        || interpolators.size() < r.minInterps) {
                    return compileAndBind(fn);
                }
                if (r.staticKernel != null) {
                    return r.staticKernel;
                }
                if (r.emptyBeardifierKernel != null && isEmptyBeardifier(beardifier)) {
                    return r.emptyBeardifierKernel;
                }
                Object[] bindings = r.templateBindings.clone();
                byte[] kinds = r.dynSrcKind;
                int[] dests = r.dynDestIdx;
                int[] srcs = r.dynSrcIdx;
                for (int i = 0; i < dests.length; i++) {
                    Object val = switch (kinds[i]) {
                        case SRC_FLAT -> flatCaches.get(srcs[i]);
                        case SRC_CACHE2D -> cache2Ds.get(srcs[i]);
                        case SRC_BEARDIFIER -> beardifier != null ? beardifier : Beardifier.EMPTY;
                        default -> interpolators.get(srcs[i]);
                    };
                    bindings[dests[i]] = val;
                }
                return new BoundKernel(r.kernel, bindings, r.usesInterpolator);
            }

            BoundKernel bound = compileAndBind(fn);
            if (bound == null) {
                this.slots.putIfAbsent(key, DISABLED);
                return null;
            }

            Object[] rawBindings = bound.bindings();
            Object[] templateBindings = new Object[rawBindings.length];
            int dynCount = 0;
            byte[] tmpKinds = new byte[rawBindings.length];
            int[] tmpDests = new int[rawBindings.length];
            int[] tmpSrcs = new int[rawBindings.length];
            int minFlats = 0;
            int minCache2Ds = 0;
            int minInterps = 0;
            boolean ok = true;
            boolean sawUnknownModDf = false;

            for (int i = 0; i < rawBindings.length; i++) {
                Object b = rawBindings[i];
                int idx;
                if (b instanceof FlatCacheAccess) {
                    idx = identityIndexOf(flatCaches, b);
                    if (idx < 0) {
                        ok = false;
                        break;
                    }
                    tmpKinds[dynCount] = SRC_FLAT;
                    tmpDests[dynCount] = i;
                    tmpSrcs[dynCount] = idx;
                    dynCount++;
                    if (idx + 1 > minFlats) minFlats = idx + 1;
                } else if (b instanceof Cache2DAccess) {
                    idx = identityIndexOf(cache2Ds, b);
                    if (idx < 0) {
                        ok = false;
                        break;
                    }
                    tmpKinds[dynCount] = SRC_CACHE2D;
                    tmpDests[dynCount] = i;
                    tmpSrcs[dynCount] = idx;
                    dynCount++;
                    if (idx + 1 > minCache2Ds) minCache2Ds = idx + 1;
                } else if (b instanceof NoiseInterpolatorAccess) {
                    idx = identityIndexOf(interpolators, b);
                    if (idx < 0) {
                        ok = false;
                        break;
                    }
                    tmpKinds[dynCount] = SRC_INTERP;
                    tmpDests[dynCount] = i;
                    tmpSrcs[dynCount] = idx;
                    dynCount++;
                    if (idx + 1 > minInterps) minInterps = idx + 1;
                } else if (b instanceof DensityFunctions.BeardifierOrMarker && b == beardifier) {
                    tmpKinds[dynCount] = SRC_BEARDIFIER;
                    tmpDests[dynCount] = i;
                    tmpSrcs[dynCount] = 0;
                    dynCount++;
                } else if (isKnownStatelessBinding(b)) {
                    templateBindings[i] = b;
                } else if (b instanceof DensityFunction df && isChunkIndependentModDf(df)) {
                    sawUnknownModDf = true;
                    templateBindings[i] = b;
                } else {
                    ok = false;
                    break;
                }
            }

            if (ok && sawUnknownModDf) {
                Object[] prev = this.pendingBindings.get(key);
                if (prev == null) {
                    this.pendingBindings.putIfAbsent(key, rawBindings);
                    return bound;
                }
                if (prev.length != rawBindings.length) {
                    this.pendingBindings.remove(key);
                    ok = false;
                } else {
                    for (int i = 0; i < rawBindings.length; i++) {
                        if (templateBindings[i] != null && prev[i] != rawBindings[i]
                                && !(prev[i].getClass() == rawBindings[i].getClass()
                                        && prev[i] instanceof Record
                                        && prev[i].equals(rawBindings[i]))) {
                            this.pendingBindings.remove(key);
                            ok = false;
                            break;
                        }
                    }
                }
            }

            if (!ok) {
                this.slots.putIfAbsent(key, UNCACHEABLE);
                return bound;
            }
            if (sawUnknownModDf) {
                this.pendingBindings.remove(key);
            }

            BoundKernel staticKernel = dynCount == 0 ? bound : null;
            BoundKernel emptyBeardifierKernel = null;
            if (dynCount > 0) {
                boolean beardifierOnly = true;
                for (int i = 0; i < dynCount; i++) {
                    if (tmpKinds[i] != SRC_BEARDIFIER) {
                        beardifierOnly = false;
                        break;
                    }
                }
                if (beardifierOnly) {
                    Object[] emptyBindings = templateBindings.clone();
                    for (int i = 0; i < dynCount; i++) {
                        emptyBindings[tmpDests[i]] = Beardifier.EMPTY;
                    }
                    emptyBeardifierKernel = new BoundKernel(bound.kernel(), emptyBindings, bound.usesInterpolator());
                }
            }
            CompiledRecipe recipe = new CompiledRecipe(
                    bound.kernel(),
                    templateBindings,
                    bound.usesInterpolator(),
                    Arrays.copyOf(tmpKinds, dynCount),
                    Arrays.copyOf(tmpDests, dynCount),
                    Arrays.copyOf(tmpSrcs, dynCount),
                    minFlats,
                    minCache2Ds,
                    minInterps,
                    fn.getClass(),
                    staticKernel,
                    emptyBeardifierKernel
            );
            this.slots.putIfAbsent(key, recipe);
            return bound;
        }

        private static boolean isEmptyBeardifier(Object beardifier) {
            return ModCompat.isEffectivelyEmptyBeardifier(beardifier);
        }

        private static int identityIndexOf(List<?> list, Object target) {
            for (int i = 0, n = list.size(); i < n; i++) {
                if (list.get(i) == target) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean isKnownStatelessBinding(Object b) {
            if (b instanceof NormalNoise
                    || b instanceof BlendedNoise
                    || b instanceof JitSupport.FlatSpline
                    || b == Beardifier.EMPTY
                    || (b instanceof BeardifierAccess ba && ba.chunking$getAffectedBox() == null
                        && !ModCompat.hasMoogEnhancedBeardifier(b))
                    || (b instanceof DensityFunction df && ModCompat.isStatelessModDensityFunction(df))) {
                return true;
            }
            if (b == null) {
                return false;
            }
            String sn = b.getClass().getSimpleName();
            return "BlendAlpha".equals(sn) || "BlendOffset".equals(sn) || "BeardifierMarker".equals(sn);
        }

        private static final ClassValue<Boolean> CHUNK_SAFE_MOD_DF_CLASS = new ClassValue<>() {
            @Override
            protected Boolean computeValue(Class<?> cls) {
                for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        Class<?> ft = f.getType();
                        if (NoiseChunk.class.isAssignableFrom(ft)
                                || Beardifier.class.isAssignableFrom(ft)
                                || net.minecraft.world.level.chunk.ChunkAccess.class.isAssignableFrom(ft)
                                || net.minecraft.world.level.LevelAccessor.class.isAssignableFrom(ft)
                                || ft.getName().startsWith("net.minecraft.world.level.levelgen.NoiseChunk$")) {
                            return Boolean.FALSE;
                        }
                    }
                }
                return Boolean.TRUE;
            }
        };

        static boolean isChunkIndependentModDf(DensityFunction df) {
            if (df == null || ModCompat.isVanillaClass(df.getClass())
                    || !CHUNK_SAFE_MOD_DF_CLASS.get(df.getClass())) {
                return false;
            }
            boolean[] ok = { true };
            df.mapAll(d -> {
                if (!ok[0] || d == null) {
                    return d;
                }
                if (d instanceof FlatCacheAccess
                        || d instanceof Cache2DAccess
                        || d instanceof NoiseInterpolatorAccess
                        || d instanceof DensityFunctions.BeardifierOrMarker
                        || d.getClass().getName().startsWith("net.minecraft.world.level.levelgen.NoiseChunk$")) {
                    ok[0] = false;
                }
                return d;
            });
            return ok[0];
        }
    }

    private static final ClassDesc CD_OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CD_OBJECT_ARR = ClassDesc.ofDescriptor("[Ljava/lang/Object;");
    private static final ClassDesc CD_INT_ARR = ClassDesc.ofDescriptor("[I");
    private static final ClassDesc CD_DOUBLE_ARR = ClassDesc.ofDescriptor("[D");
    private static final ClassDesc CD_FLOAT_ARR = ClassDesc.ofDescriptor("[F");
    private static final ClassDesc CD_CLASS = ConstantDescs.CD_Class;
    private static final ClassDesc CD_STRING = ConstantDescs.CD_String;
    private static final ClassDesc CD_LOOKUP = MethodHandles.Lookup.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_METHOD_HANDLES = MethodHandles.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_KERNEL = JitSupport.Kernel.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_SUPPORT = JitSupport.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_NOISE_CHUNK = NoiseChunk.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_NORMAL_NOISE = NormalNoise.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_BLENDED_NOISE = BlendedNoise.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_DF = DensityFunction.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_FLAT_SPLINE = JitSupport.FlatSpline.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_INTERP_ACCESS = NoiseInterpolatorAccess.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_MATH = Math.class.describeConstable().orElseThrow();
    private static final ClassDesc CD_MTH = Mth.class.describeConstable().orElseThrow();

    private static final MethodTypeDesc MTD_EVAL_BATCH = MethodTypeDesc.of(
            ConstantDescs.CD_void,
            CD_INT_ARR, CD_INT_ARR, CD_INT_ARR, CD_DOUBLE_ARR,
            ConstantDescs.CD_int, CD_OBJECT_ARR, CD_NOISE_CHUNK, ConstantDescs.CD_boolean);

    private static final ClassValue<Map<String, MethodHandle>> PROPERTY_GETTERS = new ClassValue<>() {
        @Override
        protected Map<String, MethodHandle> computeValue(Class<?> cls) {
            HashMap<String, MethodHandle> map = new HashMap<>();
            MethodHandles.Lookup lookup;
            try {
                lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());
            } catch (IllegalAccessException ignored) {
                lookup = MethodHandles.lookup();
            }
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                MethodHandles.Lookup cLookup;
                try {
                    cLookup = MethodHandles.privateLookupIn(c, MethodHandles.lookup());
                } catch (IllegalAccessException ignored) {
                    cLookup = lookup;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                        MethodHandle mh = cLookup.unreflect(m).asType(MethodType.methodType(Object.class, Object.class));
                        map.putIfAbsent(m.getName(), mh);
                    } catch (Throwable ignored) {
                    }
                }
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        MethodHandle mh = cLookup.unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
                        map.putIfAbsent(f.getName(), mh);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return Map.copyOf(map);
        }
    };

    private static final ClassValue<MethodHandle> TRANSPARENT_MOD_DF_GETTER = new ClassValue<>() {
        private static final String DF_DESC = "Lnet/minecraft/world/level/levelgen/DensityFunction;";
        private static final String DF_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction";
        private static final String COMPUTE_DESC = "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D";

        @Override
        protected MethodHandle computeValue(Class<?> cls) {
            if (ModCompat.isVanillaClass(cls)
                    || FlatCacheAccess.class.isAssignableFrom(cls)
                    || Cache2DAccess.class.isAssignableFrom(cls)
                    || NoiseInterpolatorAccess.class.isAssignableFrom(cls)) {
                return null;
            }
            if ("dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction"
                    .equals(cls.getName())) {
                MethodHandle mh = PROPERTY_GETTERS.get(cls).get("full");
                return mh != null ? mh.asType(MethodType.methodType(DensityFunction.class, Object.class)) : null;
            }
            // MarkerOrMarked's default compute() strictly delegates to wrapped().compute(ctx).
            if (DensityFunctions.MarkerOrMarked.class.isAssignableFrom(cls)) {
                try {
                    Method computeM = cls.getMethod("compute", DensityFunction.FunctionContext.class);
                    if (computeM.getDeclaringClass() == DensityFunctions.MarkerOrMarked.class) {
                        Method wrappedM = DensityFunctions.MarkerOrMarked.class.getMethod("wrapped");
                        return MethodHandles.publicLookup().unreflect(wrappedM)
                                .asType(MethodType.methodType(DensityFunction.class, Object.class));
                    }
                } catch (Throwable ignored) {
                }
            }
            // Prove transparency from compute() bytecode (strict 5-instruction field/getter delegation)
            // so math wrappers like Sin/Invert/Negate are never misclassified as transparent.
            String res = "/" + cls.getName().replace('.', '/') + ".class";
            try (var in = cls.getResourceAsStream(res)) {
                if (in == null) {
                    return null;
                }
                ClassModel cm = CLASS_FILE.parse(in.readAllBytes());
                String selfInternal = cls.getName().replace('.', '/');
                for (MethodModel mm : cm.methods()) {
                    if (!mm.methodName().equalsString("compute") || !mm.methodType().equalsString(COMPUTE_DESC)) {
                        continue;
                    }
                    CodeModel code = mm.code().orElse(null);
                    if (code == null) {
                        return null;
                    }
                    Instruction[] insts = new Instruction[6];
                    int count = 0;
                    for (CodeElement elem : code) {
                        if (elem instanceof Instruction inst) {
                            if (count >= 6) {
                                return null;
                            }
                            insts[count++] = inst;
                        }
                    }
                    if (count != 5) {
                        return null;
                    }
                    if (!(insts[0] instanceof LoadInstruction l0 && l0.slot() == 0)
                            || !(insts[2] instanceof LoadInstruction l1 && l1.slot() == 1)
                            || !(insts[3] instanceof InvokeInstruction inv
                                && inv.opcode() == Opcode.INVOKEINTERFACE
                                && inv.owner().name().equalsString(DF_INTERNAL)
                                && inv.name().equalsString("compute")
                                && inv.type().equalsString(COMPUTE_DESC))
                            || !(insts[4] instanceof ReturnInstruction ret && ret.opcode() == Opcode.DRETURN)) {
                        return null;
                    }
                    if (insts[1] instanceof FieldInstruction fi
                            && fi.opcode() == Opcode.GETFIELD
                            && fi.owner().name().equalsString(selfInternal)
                            && fi.type().equalsString(DF_DESC)) {
                        Field f = cls.getDeclaredField(fi.name().stringValue());
                        f.setAccessible(true);
                        return MethodHandles.privateLookupIn(cls, MethodHandles.lookup())
                                .unreflectGetter(f)
                                .asType(MethodType.methodType(DensityFunction.class, Object.class));
                    }
                    if (insts[1] instanceof InvokeInstruction getInv
                            && (getInv.opcode() == Opcode.INVOKEVIRTUAL || getInv.opcode() == Opcode.INVOKESPECIAL)
                            && getInv.owner().name().equalsString(selfInternal)
                            && getInv.type().equalsString("()" + DF_DESC)) {
                        String getterName = getInv.name().stringValue();
                        for (MethodModel gm : cm.methods()) {
                            if (gm.methodName().equalsString(getterName) && gm.methodType().equalsString("()" + DF_DESC)) {
                                CodeModel gc = gm.code().orElse(null);
                                if (gc == null) {
                                    return null;
                                }
                                Instruction[] gi = new Instruction[4];
                                int gCount = 0;
                                for (CodeElement ge : gc) {
                                    if (ge instanceof Instruction gInst) {
                                        if (gCount >= 4) {
                                            return null;
                                        }
                                        gi[gCount++] = gInst;
                                    }
                                }
                                if (gCount == 3
                                        && gi[0] instanceof LoadInstruction gl0 && gl0.slot() == 0
                                        && gi[1] instanceof FieldInstruction gfi && gfi.opcode() == Opcode.GETFIELD
                                        && gfi.owner().name().equalsString(selfInternal)
                                        && gfi.type().equalsString(DF_DESC)
                                        && gi[2] instanceof ReturnInstruction gret && gret.opcode() == Opcode.ARETURN) {
                                    Field f = cls.getDeclaredField(gfi.name().stringValue());
                                    f.setAccessible(true);
                                    return MethodHandles.privateLookupIn(cls, MethodHandles.lookup())
                                            .unreflectGetter(f)
                                            .asType(MethodType.methodType(DensityFunction.class, Object.class));
                                }
                            }
                        }
                    }
                    return null;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    };

    public static DensityFunction unwrapTransparentModDf(DensityFunction df) {
        if (df == null || ModCompat.isVanillaClass(df.getClass())) {
            return df;
        }
        MethodHandle mh = TRANSPARENT_MOD_DF_GETTER.get(df.getClass());
        if (mh == null) {
            return df;
        }
        try {
            DensityFunction inner = (DensityFunction) mh.invokeExact((Object) df);
            return inner != null ? inner : df;
        } catch (Throwable ignored) {
            return df;
        }
    }

    private NoiseJit() {}

    /**
     * Bound per-chunk or standalone executable kernel with its canonical chunk-local bindings array.
     */
    public record BoundKernel(JitSupport.Kernel kernel, Object[] bindings, boolean usesInterpolator) {
        public void evalBatch(int[] bx, int[] by, int[] bz, double[] out, int count, NoiseChunk nc, boolean constantYColumn) {
            JitSupport.enterJitScratchFrame();
            try {
                this.kernel.evalBatch(bx, by, bz, out, count, this.bindings, nc, constantYColumn);
            } finally {
                JitSupport.exitJitScratchFrame();
            }
        }
    }

    /**
     * Analyzes, compiles (once globally per canonical template), and binds a {@link DensityFunction} DAG.
     */
    public static BoundKernel compileAndBind(DensityFunction root) {
        IdentityKey sKey = new IdentityKey(root);
        BoundKernel statelessHit = STATELESS_CACHE.getIfPresent(sKey);
        if (statelessHit != null) {
            return statelessHit;
        }
        GraphBuilder gb = new GraphBuilder();
        IrNode rootIr = gb.visit(root, true);
        Object[] bindings = gb.bindings.toArray();
        boolean usesInterp = gb.interpSlotCount > 0;
        TemplateKey key = new TemplateKey(gb.structuralKey(), gb.consts.toArray());

        CompiledTemplate template = TEMPLATE_CACHE.getIfPresent(key);
        if (template == null) {
            try {
                template = TEMPLATE_CACHE.get(key, () -> new CompiledTemplate(emitHiddenClass(gb, rootIr, key.consts)));
            } catch (Throwable t) {
                TEMPLATE_CACHE.put(key, FAILED_TEMPLATE);
                if (LOGGED_JIT_FAILURES.add(key.structure)) {
                    ChunkingMod.LOGGER.warn("Chunking Density JIT compilation fallback for {}: {}", root.getClass().getName(), t.toString());
                }
                return null;
            }
        }
        if (template == FAILED_TEMPLATE || template.kernel == null) {
            return null;
        }
        BoundKernel bound = new BoundKernel(template.kernel, bindings, usesInterp);
        if (!gb.hasChunkLocal) {
            STATELESS_CACHE.put(sKey, bound);
        }
        return bound;
    }

    private static JitSupport.Kernel emitHiddenClass(GraphBuilder gb, IrNode rootIr, Object[] consts) throws Exception {
        String internalPkg = NoiseJit.class.getPackageName();
        String className = internalPkg + ".ChunkingJitKernel$" + CLASS_SEQ.getAndIncrement();
        ClassDesc thisCd = ClassDesc.of(className);

        byte[] bytes = CLASS_FILE.build(thisCd, cb -> {
            cb.withVersion(ClassFile.JAVA_25_VERSION, 0);
            cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
            cb.withInterfaceSymbols(CD_KERNEL);
            cb.withField("CONSTS", CD_OBJECT_ARR, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);

            cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0);
                code.invokespecial(CD_OBJECT, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                code.return_();
            });

            cb.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC, code -> {
                code.invokestatic(CD_METHOD_HANDLES, "lookup", MethodTypeDesc.of(CD_LOOKUP));
                code.loadConstant(ConstantDescs.DEFAULT_NAME);
                code.loadConstant(CD_OBJECT_ARR);
                code.invokestatic(CD_METHOD_HANDLES, "classData",
                        MethodTypeDesc.of(CD_OBJECT, CD_LOOKUP, CD_STRING, CD_CLASS));
                code.checkcast(CD_OBJECT_ARR);
                code.putstatic(thisCd, "CONSTS", CD_OBJECT_ARR);
                code.return_();
            });

            cb.withMethodBody("evalBatch", MTD_EVAL_BATCH, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, code ->
                    new MethodEmitter(thisCd, code, gb.topoOrder, rootIr, gb.interpSlotCount, gb.maxFlatSplineCoords).emit());
        });

        MethodHandles.Lookup hiddenLookup = MethodHandles.lookup().defineHiddenClassWithClassData(
                bytes, consts, true);
        try {
            return (JitSupport.Kernel) hiddenLookup
                    .findConstructor(hiddenLookup.lookupClass(), MethodType.methodType(void.class))
                    .invoke();
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

        // DAG Analysis & Structural Key Construction
        private static final class TemplateKey {
        final String structure;
        final Object[] consts;
        final int hash;

        TemplateKey(String structure, Object[] consts) {
            this.structure = structure;
            this.consts = consts;
            int h = structure.hashCode();
            for (Object c : consts) {
                h = 31 * h + System.identityHashCode(c);
            }
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TemplateKey other)) return false;
            if (this.hash != other.hash || !this.structure.equals(other.structure)) return false;
            if (this.consts.length != other.consts.length) return false;
            for (int i = 0; i < this.consts.length; i++) {
                if (this.consts[i] != other.consts[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private record CompiledTemplate(JitSupport.Kernel kernel) {}

    private static final class GraphBuilder {
        final Map<DensityFunction, IrNode> visited = new IdentityHashMap<>();
        final Map<Object, Integer> bindingSlotMap = new IdentityHashMap<>();
        final Map<Object, Integer> constSlotMap = new IdentityHashMap<>();
        final List<Object> bindings = new ArrayList<>();
        final List<Object> consts = new ArrayList<>();
        final List<IrNode> topoOrder = new ArrayList<>();
        int interpSlotCount = 0;
        int maxFlatSplineCoords = 0;
        boolean hasChunkLocal = false;

        IrNode visit(DensityFunction raw, boolean unconditional) {
            DensityFunction node = unwrapTransparent(raw);
            IrNode existing = this.visited.get(node);
            if (existing != null) {
                existing.refCount++;
                if (unconditional && !existing.unconditional) {
                    markUnconditionalRecursive(existing);
                }
                return existing;
            }

            IrNode ir = buildNode(node, unconditional);
            ir.refCount = 1;
            ir.unconditional = unconditional;
            this.visited.put(node, ir);
            this.topoOrder.add(ir);
            return ir;
        }

        private void markUnconditionalRecursive(IrNode ir) {
            if (ir.unconditional) return;
            ir.unconditional = true;
            for (IrNode ch : ir.children()) {
                markUnconditionalRecursive(ch);
            }
        }

        private DensityFunction unwrapTransparent(DensityFunction df) {
            int guard = 0;
            while (guard++ < 32) {
                if (df instanceof DensityFunctions.HolderHolder hh) {
                    df = hh.function().value();
                    continue;
                }
                Class<?> dfClass = df.getClass();
                if (ModCompat.isVanillaClass(dfClass)) {
                    String cls = dfClass.getSimpleName();
                    if ("CacheOnce".equals(cls) || "CacheAllInCell".equals(cls) || "Marker".equals(cls)) {
                        if (df instanceof DensityFunctions.MarkerOrMarked mom) {
                            df = mom.wrapped();
                            continue;
                        }
                    }
                    break;
                }
                DensityFunction unwrapped = unwrapTransparentModDf(df);
                if (unwrapped != df) {
                    df = unwrapped;
                    continue;
                }
                break;
            }
            return df;
        }

        private int bindObj(Object obj) {
            Integer idx = this.bindingSlotMap.get(obj);
            if (idx != null) {
                return idx;
            }
            int next = this.bindings.size();
            this.bindings.add(obj);
            this.bindingSlotMap.put(obj, next);
            return next;
        }

        private IrNode addSynthetic(IrNode ir, boolean unconditional) {
            ir.refCount = 1;
            ir.unconditional = unconditional;
            this.topoOrder.add(ir);
            return ir;
        }

        private int addConst(Object obj) {
            Integer idx = this.constSlotMap.get(obj);
            if (idx != null) {
                return idx;
            }
            int next = this.consts.size();
            this.consts.add(obj);
            this.constSlotMap.put(obj, next);
            return next;
        }

        private IrNode buildNode(DensityFunction node, boolean unconditional) {
            if (node instanceof FlatCacheAccess) {
                this.hasChunkLocal = true;
                return new IrFlatCache(bindObj(node));
            }
            if (node instanceof Cache2DAccess) {
                this.hasChunkLocal = true;
                return new IrCache2D(bindObj(node));
            }
            if (node instanceof NoiseInterpolatorAccess) {
                this.hasChunkLocal = true;
                int slot = bindObj(node);
                int interpOrdinal = this.interpSlotCount++;
                return new IrNoiseInterpolator(slot, interpOrdinal);
            }

            String cls = node.getClass().getSimpleName();
            if (ModCompat.isVanillaDensityFunctionsInner(node.getClass())) {
                switch (cls) {
                case "Constant" -> {
                    double v = invokeDouble(node, "value");
                    return new IrConst(v);
                }
                case "YClampedGradient" -> {
                    int fromY = invokeInt(node, "fromY");
                    int toY = invokeInt(node, "toY");
                    double fromValue = invokeDouble(node, "fromValue");
                    double toValue = invokeDouble(node, "toValue");
                    return new IrYClampedGradient(fromY, toY, fromValue, toValue);
                }
                case "Clamp" -> {
                    IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                    double min = invokeDouble(node, "minValue");
                    double max = invokeDouble(node, "maxValue");
                    return new IrClamp(in, min, max);
                }
                case "Mapped" -> {
                    int ord = ((Enum<?>) invokeObj(node, "type")).ordinal();
                    IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                    return new IrMapped(ord, in);
                }
                case "MulOrAdd" -> {
                    int ord = ((Enum<?>) invokeObj(node, "specificType")).ordinal();
                    IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                    double arg = invokeDouble(node, "argument");
                    return new IrMulOrAdd(ord, in, arg);
                }
                case "Ap2" -> {
                    DensityFunctions.TwoArgumentSimpleFunction two = (DensityFunctions.TwoArgumentSimpleFunction) node;
                    int ord = ((Enum<?>) invokeObj(two, "type")).ordinal();
                    IrNode a1 = visit(two.argument1(), unconditional);
                    boolean a2Uncond = unconditional && switch (ord) {
                        case 0 -> true; // ADD always evaluates both
                        case 1 -> two.argument1().minValue() > 0.0D || two.argument1().maxValue() < 0.0D; // MUL never 0
                        case 2 -> two.argument1().minValue() > two.argument2().maxValue(); // MIN never short-circuits
                        case 3 -> two.argument1().maxValue() < two.argument2().minValue(); // MAX never short-circuits
                        default -> false;
                    };
                    IrNode a2 = visit(two.argument2(), a2Uncond);
                    double bMin = two.argument2().minValue();
                    double bMax = two.argument2().maxValue();
                    return new IrAp2(ord, a1, a2, bMin, bMax);
                }
                case "RangeChoice" -> {
                    IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                    double minInc = invokeDouble(node, "minInclusive");
                    double maxExc = invokeDouble(node, "maxExclusive");
                    IrNode inR = visit((DensityFunction) invokeObj(node, "whenInRange"), false);
                    IrNode outR = visit((DensityFunction) invokeObj(node, "whenOutOfRange"), false);
                    return new IrRangeChoice(in, minInc, maxExc, inR, outR);
                }
                case "IntervalSelect" -> {
                    IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                    DoubleList thrList = (DoubleList) invokeObj(node, "thresholds");
                    @SuppressWarnings("unchecked")
                    List<DensityFunction> fns = (List<DensityFunction>) invokeObj(node, "functions");
                    double[] thr = thrList.toDoubleArray();
                    IrNode[] branches = new IrNode[fns.size()];
                    for (int i = 0; i < fns.size(); i++) {
                        branches[i] = visit(fns.get(i), false);
                    }
                    return new IrIntervalSelect(in, thr, branches);
                }
                case "Noise" -> {
                    DensityFunction.NoiseHolder nh = (DensityFunction.NoiseHolder) invokeObj(node, "noise");
                    NormalNoise nn = nh.noise();
                    if (nn == null) {
                        return new IrConst(0.0D);
                    }
                    double xzScale = invokeDouble(node, "xzScale");
                    double yScale = invokeDouble(node, "yScale");
                    return new IrNoise(bindObj(nn), xzScale, yScale);
                }
                case "ShiftedNoise" -> {
                    DensityFunction.NoiseHolder nh = (DensityFunction.NoiseHolder) invokeObj(node, "noise");
                    NormalNoise nn = nh.noise();
                    if (nn == null) {
                        return new IrConst(0.0D);
                    }
                    IrNode sx = visit((DensityFunction) invokeObj(node, "shiftX"), unconditional);
                    IrNode sy = visit((DensityFunction) invokeObj(node, "shiftY"), unconditional);
                    IrNode sz = visit((DensityFunction) invokeObj(node, "shiftZ"), unconditional);
                    double xzScale = invokeDouble(node, "xzScale");
                    double yScale = invokeDouble(node, "yScale");
                    return new IrShiftedNoise(bindObj(nn), sx, sy, sz, xzScale, yScale);
                }
                case "ShiftA" -> {
                    NormalNoise nn = ((DensityFunction.NoiseHolder) invokeObj(node, "offsetNoise")).noise();
                    return nn == null ? new IrConst(0.0D) : new IrShift(0, bindObj(nn));
                }
                case "ShiftB" -> {
                    NormalNoise nn = ((DensityFunction.NoiseHolder) invokeObj(node, "offsetNoise")).noise();
                    return nn == null ? new IrConst(0.0D) : new IrShift(1, bindObj(nn));
                }
                case "Shift" -> {
                    NormalNoise nn = ((DensityFunction.NoiseHolder) invokeObj(node, "offsetNoise")).noise();
                    return nn == null ? new IrConst(0.0D) : new IrShift(2, bindObj(nn));
                }
                case "BlendDensity" -> {
                    if (node instanceof DensityFunctions.MarkerOrMarked mom) {
                        IrNode inner = visit(mom.wrapped(), unconditional);
                        return new IrBlendDensity(inner);
                    }
                }
                case "BlendAlpha" -> {
                    return new IrBlendMarker(true, bindObj(node));
                }
                case "BlendOffset" -> {
                    return new IrBlendMarker(false, bindObj(node));
                }
                case "Spline" -> {
                    CubicSpline<DensityFunctions.Spline.Coordinate> cs = ((DensityFunctions.Spline) node).spline();
                    int totalNodes = countSplineNodes(cs, 0);
                    if (totalNodes <= INLINE_SPLINE_NODE_LIMIT) {
                        InlineSpline inline = buildInlineSpline(cs, unconditional);
                        return new IrInlineSpline(inline);
                    }
                    List<IrNode> coordIr = new ArrayList<>();
                    Map<DensityFunction, Integer> coordMap = new IdentityHashMap<>();
                    JitSupport.FlatSpline flat = flattenSpline(cs, coordMap, coordIr, unconditional);
                    if (coordIr.size() > this.maxFlatSplineCoords) {
                        this.maxFlatSplineCoords = coordIr.size();
                    }
                    return new IrFlatSpline(bindObj(flat), coordIr.toArray(IrNode[]::new));
                }
                default -> {}
            }
            } else if (ModCompat.isStatelessModDensityFunction(node)) {
                switch (node.getClass().getName()) {
                    case "dev.worldgen.tectonic.worldgen.densityfunction.ConfigConstant" -> {
                        return new IrConst(invokeDouble(node, "value"));
                    }
                    case "dev.worldgen.tectonic.worldgen.densityfunction.Invert" -> {
                        IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                        return new IrMapped(5, in);
                    }
                    case "dev.worldgen.tectonic.worldgen.densityfunction.ConfigClamp" -> {
                        DensityFunction minDf = (DensityFunction) invokeObj(node, "min");
                        DensityFunction maxDf = (DensityFunction) invokeObj(node, "max");
                        if (minDf != null && maxDf != null
                                && minDf.minValue() == minDf.maxValue()
                                && maxDf.minValue() == maxDf.maxValue()
                                && minDf.minValue() <= maxDf.minValue()) {
                            IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                            return new IrClamp(in, minDf.minValue(), maxDf.minValue());
                        } else if (minDf != null && maxDf != null) {
                            IrNode in = visit((DensityFunction) invokeObj(node, "input"), unconditional);
                            IrNode minIr = visit(minDf, unconditional);
                            IrNode maxIr = visit(maxDf, unconditional);
                            IrNode clampedLo = addSynthetic(
                                    new IrAp2(3, in, minIr, minDf.minValue(), minDf.maxValue()), unconditional);
                            return new IrAp2(2, clampedLo, maxIr, maxDf.minValue(), maxDf.maxValue());
                        }
                    }
                    case "dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise" -> {
                        DensityFunction.NoiseHolder holder = (DensityFunction.NoiseHolder) invokeObj(node, "noise");
                        NormalNoise nn = holder != null ? holder.noise() : null;
                        double scale = invokeDouble(node, "scale");
                        double multiplier = invokeDouble(node, "multiplier");
                        double offset = invokeDouble(node, "offset");
                        boolean smootherScaling = Boolean.TRUE.equals(invokeObj(node, "smootherScaling"));
                        if (nn == null || multiplier == 0.0D) {
                            return new IrConst(offset);
                        }
                        IrNode sx = visit((DensityFunction) invokeObj(node, "shiftX"), unconditional);
                        IrNode sz = visit((DensityFunction) invokeObj(node, "shiftZ"), unconditional);
                        IrNode zeroY = addSynthetic(new IrConst(0.0D), unconditional);
                        IrNode sampled = new IrShiftedNoise(
                                bindObj(nn), sx, zeroY, sz, scale, 0.0D, smootherScaling);
                        if (multiplier != 1.0D) {
                            sampled = new IrMulOrAdd(0, addSynthetic(sampled, unconditional), multiplier);
                        }
                        if (offset != 0.0D) {
                            sampled = new IrMulOrAdd(1, addSynthetic(sampled, unconditional), offset);
                        }
                        return sampled;
                    }
                    default -> {}
                }
            }
            if (node instanceof BlendedNoise bn) {
                return new IrBlendedNoise(bindObj(bn));
            }

            if (ModCompat.isEffectivelyEmptyBeardifier(node)) {
                return new IrConst(0.0D);
            }
            if (!ModCompat.isVanillaClass(node.getClass())) {
                if (!NoiseChunkPlan.isChunkIndependentModDf(node)) {
                    this.hasChunkLocal = true;
                }
                return new IrBindingFallback(bindObj(node));
            }
            if (node.getClass().getName().startsWith("net.minecraft.world.level.levelgen.NoiseChunk$")
                    || node instanceof DensityFunctions.BeardifierOrMarker
                    || cls.contains("Beardifier")) {
                this.hasChunkLocal = true;
                return new IrBindingFallback(bindObj(node));
            }
            return new IrConstFallback(addConst(node));
        }

        private int countSplineNodes(CubicSpline<DensityFunctions.Spline.Coordinate> cs, int acc) {
            if (acc > INLINE_SPLINE_NODE_LIMIT) {
                return acc;
            }
            if (cs instanceof CubicSpline.Constant) {
                return acc + 1;
            }
            CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> mp =
                    (CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>) cs;
            int total = acc + 1;
            for (CubicSpline<DensityFunctions.Spline.Coordinate> child : mp.values()) {
                total = countSplineNodes(child, total);
                if (total > INLINE_SPLINE_NODE_LIMIT) {
                    return total;
                }
            }
            return total;
        }

        private InlineSpline buildInlineSpline(CubicSpline<DensityFunctions.Spline.Coordinate> cs, boolean unconditional) {
            if (cs instanceof CubicSpline.Constant<DensityFunctions.Spline.Coordinate> c) {
                return new InlineSplineConst(c.value());
            }
            CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> mp =
                    (CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>) cs;
            IrNode coord = visit(mp.coordinate().function(), unconditional);
            float[] locs = mp.locations();
            float[] derivs = mp.derivatives();
            List<CubicSpline<DensityFunctions.Spline.Coordinate>> vals = mp.values();
            InlineSpline[] children = new InlineSpline[vals.size()];
            for (int i = 0; i < vals.size(); i++) {
                children[i] = buildInlineSpline(vals.get(i), false);
            }
            return new InlineSplineMulti(coord, locs, children, derivs);
        }

        private JitSupport.FlatSpline flattenSpline(
                CubicSpline<DensityFunctions.Spline.Coordinate> cs,
                Map<DensityFunction, Integer> coordMap,
                List<IrNode> coordIr,
                boolean unconditional
        ) {
            if (cs instanceof CubicSpline.Constant<DensityFunctions.Spline.Coordinate> c) {
                return new JitSupport.FlatConst(c.value());
            }
            CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> mp =
                    (CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate>) cs;
            DensityFunction rawCoordFn = mp.coordinate().function();
            DensityFunction unwrapped = unwrapTransparent(rawCoordFn);
            Integer cSlot = coordMap.get(unwrapped);
            if (cSlot == null) {
                cSlot = coordIr.size();
                coordMap.put(unwrapped, cSlot);
                coordIr.add(visit(rawCoordFn, unconditional));
            }
            List<CubicSpline<DensityFunctions.Spline.Coordinate>> vals = mp.values();
            JitSupport.FlatSpline[] children = new JitSupport.FlatSpline[vals.size()];
            for (int i = 0; i < vals.size(); i++) {
                children[i] = flattenSpline(vals.get(i), coordMap, coordIr, unconditional);
            }
            return new JitSupport.FlatBranch(cSlot, mp.locations(), children, mp.derivatives());
        }

                String structuralKey() {
            StringBuilder sb = new StringBuilder(512);
            for (int i = 0; i < this.topoOrder.size(); i++) {
                this.topoOrder.get(i).topoId = i;
            }
            for (IrNode n : this.topoOrder) {
                n.appendKey(sb);
                sb.append(';');
            }
            return sb.toString();
        }
    }

        // IR Nodes
        private abstract static class IrNode {
        int topoId;
        int refCount;
        boolean unconditional;
        boolean yDependent;
        boolean quartInvariant;
        int cseValueLocal = -1;
        int cseComputedFlagLocal = -1;
        int hoistedLocal = -1;
        int colSliceArrayLocal = -1;
        int colSliceSlot = -1;

        abstract boolean computeYDep();
        boolean computeQuartInv() {
            return (this instanceof IrConst) || (this instanceof IrFlatCache);
        }
        abstract boolean isCheapLeaf();
        abstract List<IrNode> children();
        abstract void appendKey(StringBuilder sb);
    }

    private sealed interface InlineSpline permits InlineSplineConst, InlineSplineMulti {}

    private record InlineSplineConst(float val) implements InlineSpline {}

    private record InlineSplineMulti(IrNode coord, float[] locs, InlineSpline[] children, float[] derivs) implements InlineSpline {}

    private static final class IrConst extends IrNode {
        final double val;
        IrConst(double val) { this.val = val; }
        @Override boolean computeYDep() { return false; }
        @Override boolean isCheapLeaf() { return true; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("C(").append(Double.doubleToRawLongBits(this.val)).append(')'); }
    }

    private static final class IrYClampedGradient extends IrNode {
        final int fromY, toY;
        final double fromVal, toVal;
        IrYClampedGradient(int fromY, int toY, double fromVal, double toVal) {
            this.fromY = fromY; this.toY = toY; this.fromVal = fromVal; this.toVal = toVal;
        }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return true; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("YG(").append(fromY).append(',').append(toY).append(',')
                    .append(Double.doubleToRawLongBits(fromVal)).append(',')
                    .append(Double.doubleToRawLongBits(toVal)).append(')');
        }
    }

    private static final class IrClamp extends IrNode {
        final IrNode in; final double min, max;
        IrClamp(IrNode in, double min, double max) { this.in = in; this.min = min; this.max = max; }
        @Override boolean computeYDep() { return this.in.yDependent; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(this.in); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("CL(").append(in.topoId).append(',')
                    .append(Double.doubleToRawLongBits(min)).append(',')
                    .append(Double.doubleToRawLongBits(max)).append(')');
        }
    }

    private static final class IrMapped extends IrNode {
        final int typeOrd; final IrNode in;
        IrMapped(int typeOrd, IrNode in) { this.typeOrd = typeOrd; this.in = in; }
        @Override boolean computeYDep() { return this.in.yDependent; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(this.in); }
        @Override void appendKey(StringBuilder sb) { sb.append("M(").append(typeOrd).append(',').append(in.topoId).append(')'); }
    }

    private static final class IrMulOrAdd extends IrNode {
        final int typeOrd; final IrNode in; final double arg;
        IrMulOrAdd(int typeOrd, IrNode in, double arg) { this.typeOrd = typeOrd; this.in = in; this.arg = arg; }
        @Override boolean computeYDep() { return this.in.yDependent; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(this.in); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("MA(").append(typeOrd).append(',').append(in.topoId).append(',')
                    .append(Double.doubleToRawLongBits(arg)).append(')');
        }
    }

    private static final class IrAp2 extends IrNode {
        final int typeOrd; final IrNode a, b; final double bMin, bMax;
        IrAp2(int typeOrd, IrNode a, IrNode b, double bMin, double bMax) {
            this.typeOrd = typeOrd; this.a = a; this.b = b; this.bMin = bMin; this.bMax = bMax;
        }
        @Override boolean computeYDep() { return a.yDependent || b.yDependent; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(a, b); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("A2(").append(typeOrd).append(',').append(a.topoId).append(',').append(b.topoId)
                    .append(',').append(Double.doubleToRawLongBits(bMin))
                    .append(',').append(Double.doubleToRawLongBits(bMax)).append(')');
        }
    }

    private static final class IrRangeChoice extends IrNode {
        final IrNode in, inRange, outRange; final double minInc, maxExc;
        IrRangeChoice(IrNode in, double minInc, double maxExc, IrNode inRange, IrNode outRange) {
            this.in = in; this.minInc = minInc; this.maxExc = maxExc; this.inRange = inRange; this.outRange = outRange;
        }
        @Override boolean computeYDep() { return in.yDependent || inRange.yDependent || outRange.yDependent; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(in, inRange, outRange); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("RC(").append(in.topoId).append(',')
                    .append(Double.doubleToRawLongBits(minInc)).append(',')
                    .append(Double.doubleToRawLongBits(maxExc)).append(',')
                    .append(inRange.topoId).append(',').append(outRange.topoId).append(')');
        }
    }

    private static final class IrIntervalSelect extends IrNode {
        final IrNode in; final double[] thresholds; final IrNode[] branches;
        IrIntervalSelect(IrNode in, double[] thresholds, IrNode[] branches) {
            this.in = in; this.thresholds = thresholds; this.branches = branches;
        }
        @Override boolean computeYDep() {
            if (in.yDependent) return true;
            for (IrNode b : branches) if (b.yDependent) return true;
            return false;
        }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() {
            List<IrNode> list = new ArrayList<>(1 + branches.length);
            list.add(in);
            list.addAll(Arrays.asList(branches));
            return list;
        }
        @Override void appendKey(StringBuilder sb) {
            sb.append("IS(").append(in.topoId).append(",[");
            for (int i = 0; i < thresholds.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(Double.doubleToRawLongBits(thresholds[i]));
            }
            sb.append(']');
            for (IrNode b : branches) sb.append(',').append(b.topoId);
            sb.append(')');
        }
    }

    private static final class IrNoise extends IrNode {
        final int noiseConstSlot; final double xzScale, yScale;
        IrNoise(int noiseConstSlot, double xzScale, double yScale) {
            this.noiseConstSlot = noiseConstSlot; this.xzScale = xzScale; this.yScale = yScale;
        }
        @Override boolean computeYDep() { return this.yScale != 0.0D; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("N(").append(noiseConstSlot).append(',')
                    .append(Double.doubleToRawLongBits(xzScale)).append(',')
                    .append(Double.doubleToRawLongBits(yScale)).append(')');
        }
    }

    private static final class IrShiftedNoise extends IrNode {
        final int noiseConstSlot; final IrNode sx, sy, sz; final double xzScale, yScale; final boolean scaleAfterShift;
        IrShiftedNoise(int noiseConstSlot, IrNode sx, IrNode sy, IrNode sz, double xzScale, double yScale) {
            this(noiseConstSlot, sx, sy, sz, xzScale, yScale, false);
        }
        IrShiftedNoise(int noiseConstSlot, IrNode sx, IrNode sy, IrNode sz, double xzScale, double yScale, boolean scaleAfterShift) {
            this.noiseConstSlot = noiseConstSlot; this.sx = sx; this.sy = sy; this.sz = sz;
            this.xzScale = xzScale; this.yScale = yScale; this.scaleAfterShift = scaleAfterShift;
        }
        @Override boolean computeYDep() { return yScale != 0.0D || sx.yDependent || sy.yDependent || sz.yDependent; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(sx, sy, sz); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("SN(").append(noiseConstSlot).append(',').append(sx.topoId).append(',')
                    .append(sy.topoId).append(',').append(sz.topoId).append(',')
                    .append(Double.doubleToRawLongBits(xzScale)).append(',')
                    .append(Double.doubleToRawLongBits(yScale)).append(',')
                    .append(scaleAfterShift ? 1 : 0).append(')');
        }
    }

    private static final class IrShift extends IrNode {
        final int kind; // 0=ShiftA, 1=ShiftB, 2=Shift
        final int noiseConstSlot;
        IrShift(int kind, int noiseConstSlot) { this.kind = kind; this.noiseConstSlot = noiseConstSlot; }
        @Override boolean computeYDep() { return kind == 2; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("SH(").append(kind).append(',').append(noiseConstSlot).append(')'); }
    }

    private static final class IrBlendedNoise extends IrNode {
        final int constSlot;
        IrBlendedNoise(int constSlot) { this.constSlot = constSlot; }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("BN(").append(constSlot).append(')'); }
    }

    private static final class IrFlatCache extends IrNode {
        final int bindingSlot;
        IrFlatCache(int bindingSlot) { this.bindingSlot = bindingSlot; }
        @Override boolean computeYDep() { return false; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("FC(").append(bindingSlot).append(')'); }
    }

    private static final class IrCache2D extends IrNode {
        final int bindingSlot;
        IrCache2D(int bindingSlot) { this.bindingSlot = bindingSlot; }
        @Override boolean computeYDep() { return false; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("C2(").append(bindingSlot).append(')'); }
    }

    private static final class IrNoiseInterpolator extends IrNode {
        final int bindingSlot;
        final int interpOrdinal;
        IrNoiseInterpolator(int bindingSlot, int interpOrdinal) {
            this.bindingSlot = bindingSlot;
            this.interpOrdinal = interpOrdinal;
        }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("NI(").append(bindingSlot).append(',').append(interpOrdinal).append(')'); }
    }

    private static final class IrBlendDensity extends IrNode {
        final IrNode inner;
        IrBlendDensity(IrNode inner) { this.inner = inner; }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(inner); }
        @Override void appendKey(StringBuilder sb) { sb.append("BD(").append(inner.topoId).append(')'); }
    }

    private static final class IrBlendMarker extends IrNode {
        final boolean isAlpha; final int bindingSlot;
        IrBlendMarker(boolean isAlpha, int bindingSlot) { this.isAlpha = isAlpha; this.bindingSlot = bindingSlot; }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append(isAlpha ? "BA(" : "BO(").append(bindingSlot).append(')'); }
    }

    private static final class IrInlineSpline extends IrNode {
        final InlineSpline spline;
        final List<IrNode> childRefs;
        IrInlineSpline(InlineSpline spline) {
            this.spline = spline;
            this.childRefs = new ArrayList<>();
            collectCoords(spline, this.childRefs);
        }
        private static void collectCoords(InlineSpline s, List<IrNode> out) {
            if (s instanceof InlineSplineMulti m) {
                out.add(m.coord);
                for (InlineSpline c : m.children) collectCoords(c, out);
            }
        }
        @Override boolean computeYDep() {
            for (IrNode n : childRefs) if (n.yDependent) return true;
            return false;
        }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return childRefs; }
        @Override void appendKey(StringBuilder sb) {
            sb.append("ISPL(");
            appendInlineSplineKey(spline, sb);
            sb.append(')');
        }
        private static void appendInlineSplineKey(InlineSpline s, StringBuilder sb) {
            if (s instanceof InlineSplineConst c) {
                sb.append('k').append(Float.floatToRawIntBits(c.val));
                return;
            }
            InlineSplineMulti m = (InlineSplineMulti) s;
            sb.append('m').append(m.coord.topoId).append(":(");
            for (int i = 0; i < m.locs.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(Float.floatToRawIntBits(m.locs[i]));
            }
            sb.append("):(");
            for (int i = 0; i < m.derivs.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(Float.floatToRawIntBits(m.derivs[i]));
            }
            sb.append(")[");
            for (InlineSpline ch : m.children) { appendInlineSplineKey(ch, sb); sb.append(','); }
            sb.append(']');
        }
    }

    private static final class IrFlatSpline extends IrNode {
        final int flatConstSlot;
        final IrNode[] coords;
        IrFlatSpline(int flatConstSlot, IrNode[] coords) {
            this.flatConstSlot = flatConstSlot; this.coords = coords;
        }
        @Override boolean computeYDep() {
            for (IrNode c : coords) if (c.yDependent) return true;
            return false;
        }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(coords); }
        @Override void appendKey(StringBuilder sb) {
            sb.append("FSPL(").append(flatConstSlot);
            for (IrNode c : coords) sb.append(',').append(c.topoId);
            sb.append(')');
        }
    }

    private static final class IrBindingFallback extends IrNode {
        final int bindingSlot;
        IrBindingFallback(int bindingSlot) { this.bindingSlot = bindingSlot; }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("FB(").append(bindingSlot).append(')'); }
    }

    private static final class IrConstFallback extends IrNode {
        final int constSlot;
        IrConstFallback(int constSlot) { this.constSlot = constSlot; }
        @Override boolean computeYDep() { return true; }
        @Override boolean isCheapLeaf() { return false; }
        @Override List<IrNode> children() { return List.of(); }
        @Override void appendKey(StringBuilder sb) { sb.append("FCN(").append(constSlot).append(')'); }
    }

        // Java 25 ClassFile Method Bytecode Emitter
        private static final class MethodEmitter {
        // Argument locals of evalBatch
        private static final int P_BX = 1;
        private static final int P_BY = 2;
        private static final int P_BZ = 3;
        private static final int P_OUT = 4;
        private static final int P_COUNT = 5;
        private static final int P_BINDINGS = 6;
        private static final int P_NOISE_CHUNK = 7;
        private static final int P_CONST_Y_COL = 8;

        private final ClassDesc thisCd;
        private final CodeBuilder cb;
        private final List<IrNode> topoOrder;
        private final IrNode root;
        private final int interpCount;
        private final int maxFlatSplineCoords;

        private int lConsts;
        private int lBlenderEmpty;
        private int lCanCellLerp;
        private int lCanQuartHoist;
        private int lUseCellArr;
        private int lCellStartX, lCellStartY, lCellStartZ;
        private int lInvCw, lInvCh;
        private int lTx, lTy, lTz;
        private int[] lInterpCorners; // 8 doubles per NoiseInterpolator
        private int[] lInterpCellArr; // separable pre-filled 128-voxel cell array per NoiseInterpolator
        private int lSplineFloatScratch;
        private int lIdx, lBx, lBy, lBz;
        private final List<IrNode> condCseNodes = new ArrayList<>();
        private final List<IrNode> hoistNodes = new ArrayList<>();
        private final List<IrNode> colSliceNodes = new ArrayList<>();
        private final List<IrNode> lazyColSliceNodes = new ArrayList<>();

        MethodEmitter(ClassDesc thisCd, CodeBuilder cb, List<IrNode> topoOrder, IrNode root, int interpCount, int maxFlatSplineCoords) {
            this.thisCd = thisCd;
            this.cb = cb;
            this.topoOrder = topoOrder;
            this.root = root;
            this.interpCount = interpCount;
            this.maxFlatSplineCoords = maxFlatSplineCoords;
        }

        void emit() {
            // Early exit on empty batch so bx[0]/bz[0] preloads are always in-bounds
            cb.iload(P_COUNT);
            Label okCount = cb.newLabel();
            cb.ifgt(okCount);
            cb.return_();
            cb.labelBinding(okCount);

            // Compute y-dependency and quart-2D invariance bottom-up in topological order
            for (IrNode n : this.topoOrder) {
                n.yDependent = n.computeYDep();
                n.quartInvariant = n.computeQuartInv();
            }

            // Allocate and initialize ALL method-level local variables upfront before any branch
            // so the Java 25 ClassFile StackMapTable generator never sees an uninitialized Top slot at join points.
            this.lConsts = cb.allocateLocal(TypeKind.REFERENCE);
            cb.getstatic(this.thisCd, "CONSTS", CD_OBJECT_ARR);
            cb.astore(this.lConsts);

            this.lBlenderEmpty = cb.allocateLocal(TypeKind.INT);
            cb.aload(P_NOISE_CHUNK);
            cb.invokestatic(CD_SUPPORT, "isBlenderEmpty", MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_NOISE_CHUNK));
            cb.istore(this.lBlenderEmpty);

            this.lCanCellLerp = cb.allocateLocal(TypeKind.INT);
            this.lCanQuartHoist = cb.allocateLocal(TypeKind.INT);
            this.lUseCellArr = cb.allocateLocal(TypeKind.INT);
            this.lCellStartX = cb.allocateLocal(TypeKind.INT);
            this.lCellStartY = cb.allocateLocal(TypeKind.INT);
            this.lCellStartZ = cb.allocateLocal(TypeKind.INT);
            this.lInvCw = cb.allocateLocal(TypeKind.DOUBLE);
            this.lInvCh = cb.allocateLocal(TypeKind.DOUBLE);
            this.lTx = cb.allocateLocal(TypeKind.DOUBLE);
            this.lTy = cb.allocateLocal(TypeKind.DOUBLE);
            this.lTz = cb.allocateLocal(TypeKind.DOUBLE);
            cb.iconst_0(); cb.istore(this.lCanCellLerp);
            cb.iconst_0(); cb.istore(this.lCanQuartHoist);
            cb.iconst_0(); cb.istore(this.lUseCellArr);
            cb.iconst_0(); cb.istore(this.lCellStartX);
            cb.iconst_0(); cb.istore(this.lCellStartY);
            cb.iconst_0(); cb.istore(this.lCellStartZ);
            cb.dconst_0(); cb.dstore(this.lInvCw);
            cb.dconst_0(); cb.dstore(this.lInvCh);
            cb.dconst_0(); cb.dstore(this.lTx);
            cb.dconst_0(); cb.dstore(this.lTy);
            cb.dconst_0(); cb.dstore(this.lTz);

            int lInterpObj = -1;
            if (this.interpCount > 0) {
                lInterpObj = cb.allocateLocal(TypeKind.REFERENCE);
                cb.aconst_null();
                cb.checkcast(CD_INTERP_ACCESS);
                cb.astore(lInterpObj);
                this.lInterpCorners = new int[this.interpCount * 8];
                for (int i = 0; i < this.lInterpCorners.length; i++) {
                    this.lInterpCorners[i] = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dconst_0();
                    cb.dstore(this.lInterpCorners[i]);
                }
                this.lInterpCellArr = new int[this.interpCount];
                for (int i = 0; i < this.interpCount; i++) {
                    this.lInterpCellArr[i] = cb.allocateLocal(TypeKind.REFERENCE);
                    cb.aconst_null();
                    cb.checkcast(CD_DOUBLE_ARR);
                    cb.astore(this.lInterpCellArr[i]);
                }
            }

            if (this.maxFlatSplineCoords > 0) {
                this.lSplineFloatScratch = cb.allocateLocal(TypeKind.REFERENCE);
                cb.loadConstant(this.maxFlatSplineCoords);
                cb.invokestatic(CD_SUPPORT, "borrowSplineFloatScratch",
                        MethodTypeDesc.of(CD_FLOAT_ARR, ConstantDescs.CD_int));
                cb.astore(this.lSplineFloatScratch);
            }

            this.lIdx = cb.allocateLocal(TypeKind.INT);
            this.lBx = cb.allocateLocal(TypeKind.INT);
            this.lBy = cb.allocateLocal(TypeKind.INT);
            this.lBz = cb.allocateLocal(TypeKind.INT);
            cb.iconst_0(); cb.istore(this.lIdx);
            cb.iconst_0(); cb.istore(this.lBx);
            cb.iconst_0(); cb.istore(this.lBy);
            cb.iconst_0(); cb.istore(this.lBz);

            // Classify hoisted 2D nodes, vertical column-slice / 3D-batch noise nodes, and per-point CSE nodes
            for (IrNode n : this.topoOrder) {
                if (n.isCheapLeaf()) continue;
                boolean shiftedColCandidate = n instanceof IrShiftedNoise sn
                        && !sn.sx.yDependent && !sn.sz.yDependent
                        && sn.sy instanceof IrConst syConst && syConst.val == 0.0D;
                boolean colEligible = n.yDependent
                        && (n instanceof IrNoise || n instanceof IrBlendedNoise
                        || (n instanceof IrShift sh && sh.kind == 2)
                        || shiftedColCandidate);
                if (colEligible && (this.colSliceNodes.size() + this.lazyColSliceNodes.size()) < MAX_COL_SLICE_SLOTS) {
                    int cal = cb.allocateLocal(TypeKind.REFERENCE);
                    cb.aconst_null();
                    cb.checkcast(CD_DOUBLE_ARR);
                    cb.astore(cal);
                    n.colSliceArrayLocal = cal;
                    if (n.unconditional) {
                        n.colSliceSlot = this.colSliceNodes.size();
                        this.colSliceNodes.add(n);
                        continue;
                    }
                    this.lazyColSliceNodes.add(n);
                }
                boolean hoistCandidate = !n.yDependent && n.unconditional
                        && (n.refCount >= 2 || n == this.root || isExpensiveLeaf(n));
                if (hoistCandidate) {
                    int hl = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dconst_0();
                    cb.dstore(hl);
                    n.hoistedLocal = hl;
                    this.hoistNodes.add(n);
                }
                if (n.refCount >= 2) {
                    int vl = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dconst_0();
                    cb.dstore(vl);
                    n.cseValueLocal = vl;
                    if (!n.unconditional) {
                        int fl = cb.allocateLocal(TypeKind.INT);
                        cb.iconst_0();
                        cb.istore(fl);
                        n.cseComputedFlagLocal = fl;
                        this.condCseNodes.add(n);
                    }
                }
            }

            if (this.interpCount > 0) {
                cb.aload(P_NOISE_CHUNK);
                cb.invokestatic(CD_SUPPORT, "canUseCellCorners", MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_NOISE_CHUNK));
                cb.dup();
                cb.istore(this.lCanCellLerp);
                Label skipCorners = cb.newLabel();
                cb.ifeq(skipCorners);

                cb.aload(P_NOISE_CHUNK);
                cb.invokestatic(CD_SUPPORT, "getCellStartX", MethodTypeDesc.of(ConstantDescs.CD_int, CD_NOISE_CHUNK));
                cb.istore(this.lCellStartX);
                cb.aload(P_NOISE_CHUNK);
                cb.invokestatic(CD_SUPPORT, "getCellStartY", MethodTypeDesc.of(ConstantDescs.CD_int, CD_NOISE_CHUNK));
                cb.istore(this.lCellStartY);
                cb.aload(P_NOISE_CHUNK);
                cb.invokestatic(CD_SUPPORT, "getCellStartZ", MethodTypeDesc.of(ConstantDescs.CD_int, CD_NOISE_CHUNK));
                cb.istore(this.lCellStartZ);

                if (this.interpCount <= 19 && this.colSliceNodes.size() <= 12) {
                    Label fallbackCorners = cb.newLabel();
                    // Fast separable Y->X->Z trilinear pre-fill for 128-voxel (4x8x4) cells
                    // matching NoiseChunkMixin.chunking$simdFillAllDirectly's (y=7..0, x=0..3, z=0..3) layout
                    cb.iload(P_CONST_Y_COL);
                    cb.ifne(fallbackCorners);
                    cb.iload(P_COUNT);
                    cb.loadConstant(128);
                    cb.if_icmpne(fallbackCorners);
                    cb.aload(P_BX); cb.iconst_0(); cb.iaload(); cb.iload(this.lCellStartX); cb.if_icmpne(fallbackCorners);
                    cb.aload(P_BY); cb.iconst_0(); cb.iaload(); cb.iload(this.lCellStartY); cb.loadConstant(7); cb.iadd(); cb.if_icmpne(fallbackCorners);
                    cb.aload(P_BZ); cb.iconst_0(); cb.iaload(); cb.iload(this.lCellStartZ); cb.if_icmpne(fallbackCorners);
                    cb.aload(P_BX); cb.loadConstant(127); cb.iaload(); cb.iload(this.lCellStartX); cb.iconst_3(); cb.iadd(); cb.if_icmpne(fallbackCorners);
                    cb.aload(P_BY); cb.loadConstant(127); cb.iaload(); cb.iload(this.lCellStartY); cb.if_icmpne(fallbackCorners);
                    cb.aload(P_BZ); cb.loadConstant(127); cb.iaload(); cb.iload(this.lCellStartZ); cb.iconst_3(); cb.iadd(); cb.if_icmpne(fallbackCorners);

                    for (IrNode n : this.topoOrder) {
                        if (n instanceof IrNoiseInterpolator ni) {
                            cb.aload(P_BINDINGS);
                            cb.loadConstant(ni.bindingSlot);
                            cb.aaload();
                            cb.aload(P_NOISE_CHUNK);
                            cb.loadConstant(ni.interpOrdinal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "fillCellInterpolator", MethodTypeDesc.of(
                                    CD_DOUBLE_ARR, CD_OBJECT, CD_NOISE_CHUNK, ConstantDescs.CD_int, ConstantDescs.CD_int));
                            cb.dup();
                            cb.astore(this.lInterpCellArr[ni.interpOrdinal]);
                            cb.ifnull(fallbackCorners);
                        }
                    }
                    cb.iconst_1();
                    cb.istore(this.lUseCellArr);
                    cb.iconst_1();
                    cb.istore(this.lCanQuartHoist);
                    cb.goto_(skipCorners);
                    cb.labelBinding(fallbackCorners);
                    for (int i = 0; i < this.interpCount; i++) {
                        cb.aconst_null();
                        cb.checkcast(CD_DOUBLE_ARR);
                        cb.astore(this.lInterpCellArr[i]);
                    }
                }

                cb.aload(P_NOISE_CHUNK);
                cb.invokestatic(CD_SUPPORT, "getInvCellWidth", MethodTypeDesc.of(ConstantDescs.CD_double, CD_NOISE_CHUNK));
                cb.dstore(this.lInvCw);
                cb.aload(P_NOISE_CHUNK);
                cb.invokestatic(CD_SUPPORT, "getInvCellHeight", MethodTypeDesc.of(ConstantDescs.CD_double, CD_NOISE_CHUNK));
                cb.dstore(this.lInvCh);

                // Quart hoisting is only valid when cellWidth <= 4 and all voxels in the batch lie in the same 4x4 quart
                Label notSingleQuart = cb.newLabel();
                cb.dload(this.lInvCw); cb.loadConstant(0.25D); cb.dcmpg(); cb.iflt(notSingleQuart);
                cb.aload(P_BX); cb.iconst_0(); cb.iaload(); cb.iconst_2(); cb.ishr();
                cb.aload(P_BX); cb.iload(P_COUNT); cb.iconst_1(); cb.isub(); cb.iaload(); cb.iconst_2(); cb.ishr();
                cb.if_icmpne(notSingleQuart);
                cb.aload(P_BZ); cb.iconst_0(); cb.iaload(); cb.iconst_2(); cb.ishr();
                cb.aload(P_BZ); cb.iload(P_COUNT); cb.iconst_1(); cb.isub(); cb.iaload(); cb.iconst_2(); cb.ishr();
                cb.if_icmpne(notSingleQuart);
                cb.iconst_1();
                cb.istore(this.lCanQuartHoist);
                cb.labelBinding(notSingleQuart);

                for (IrNode n : this.topoOrder) {
                    if (n instanceof IrNoiseInterpolator ni) {
                        int base = ni.interpOrdinal * 8;
                        cb.aload(P_BINDINGS);
                        cb.loadConstant(ni.bindingSlot);
                        cb.aaload();
                        cb.checkcast(CD_INTERP_ACCESS);
                        cb.astore(lInterpObj);
                        String[] cornerGetters = {
                                "chunking$getNoise000", "chunking$getNoise100",
                                "chunking$getNoise010", "chunking$getNoise110",
                                "chunking$getNoise001", "chunking$getNoise101",
                                "chunking$getNoise011", "chunking$getNoise111"
                        };
                        for (int c = 0; c < 8; c++) {
                            cb.aload(lInterpObj);
                            cb.invokeinterface(CD_INTERP_ACCESS, cornerGetters[c], MethodTypeDesc.of(ConstantDescs.CD_double));
                            cb.dstore(this.lInterpCorners[base + c]);
                        }
                    }
                }
                cb.labelBinding(skipCorners);
            }
            for (int li = 0; li < this.lazyColSliceNodes.size(); li++) {
                this.lazyColSliceNodes.get(li).colSliceSlot = this.colSliceNodes.size() + li;
            }

            // Pre-evaluate hoisted 2D nodes + column AVX2 slices (if constantYColumn) or 3D AVX2 batches (if count >= 4)
            if (!this.hoistNodes.isEmpty() || !this.colSliceNodes.isEmpty()) {
                cb.iload(P_CONST_Y_COL);
                Label notConstCol = cb.newLabel();
                Label donePre = cb.newLabel();
                cb.ifeq(notConstCol);
                cb.aload(P_BX); cb.iconst_0(); cb.iaload(); cb.istore(this.lBx);
                cb.iconst_0(); cb.istore(this.lBy);
                cb.aload(P_BZ); cb.iconst_0(); cb.iaload(); cb.istore(this.lBz);
                for (IrNode h : this.hoistNodes) {
                    emitRawNode(h, false);
                    cb.dstore(h.hoistedLocal);
                    if (h.cseValueLocal >= 0) {
                        cb.dload(h.hoistedLocal);
                        cb.dstore(h.cseValueLocal);
                    }
                }
                for (int slot = 0; slot < this.colSliceNodes.size(); slot++) {
                    IrNode cs = this.colSliceNodes.get(slot);
                    cb.loadConstant(slot);
                    cb.iload(P_COUNT);
                    cb.invokestatic(CD_SUPPORT, "borrowColScratch",
                            MethodTypeDesc.of(CD_DOUBLE_ARR, ConstantDescs.CD_int, ConstantDescs.CD_int));
                    cb.astore(cs.colSliceArrayLocal);
                    switch (cs) {
                        case IrNoise n -> {
                            loadBinding(n.noiseConstSlot, CD_NORMAL_NOISE);
                            cb.iload(this.lBx); cb.i2d(); cb.loadConstant(n.xzScale); cb.dmul();
                            cb.aload(P_BY);
                            cb.loadConstant(n.yScale);
                            cb.iload(this.lBz); cb.i2d(); cb.loadConstant(n.xzScale); cb.dmul();
                            cb.aload(cs.colSliceArrayLocal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "sampleNormalNoiseColumn",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE, ConstantDescs.CD_double,
                                            CD_INT_ARR, ConstantDescs.CD_double, ConstantDescs.CD_double, CD_DOUBLE_ARR, ConstantDescs.CD_int));
                        }
                        case IrShiftedNoise sn -> {
                            loadBinding(sn.noiseConstSlot, CD_NORMAL_NOISE);
                            if (sn.scaleAfterShift) {
                                cb.iload(this.lBx); cb.i2d(); emitUseNode(sn.sx); cb.dadd(); cb.loadConstant(sn.xzScale); cb.dmul();
                            } else {
                                cb.iload(this.lBx); cb.i2d(); cb.loadConstant(sn.xzScale); cb.dmul(); emitUseNode(sn.sx); cb.dadd();
                            }
                            cb.aload(P_BY);
                            cb.loadConstant(sn.yScale);
                            if (sn.scaleAfterShift) {
                                cb.iload(this.lBz); cb.i2d(); emitUseNode(sn.sz); cb.dadd(); cb.loadConstant(sn.xzScale); cb.dmul();
                            } else {
                                cb.iload(this.lBz); cb.i2d(); cb.loadConstant(sn.xzScale); cb.dmul(); emitUseNode(sn.sz); cb.dadd();
                            }
                            cb.aload(cs.colSliceArrayLocal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "sampleNormalNoiseColumn",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE, ConstantDescs.CD_double,
                                            CD_INT_ARR, ConstantDescs.CD_double, ConstantDescs.CD_double, CD_DOUBLE_ARR, ConstantDescs.CD_int));
                        }
                        case IrShift sh -> {
                            loadBinding(sh.noiseConstSlot, CD_NORMAL_NOISE);
                            cb.iload(this.lBx); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                            cb.aload(P_BY);
                            cb.iload(this.lBz); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                            cb.aload(cs.colSliceArrayLocal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "sampleShiftColumn",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE, ConstantDescs.CD_double,
                                            CD_INT_ARR, ConstantDescs.CD_double, CD_DOUBLE_ARR, ConstantDescs.CD_int));
                        }
                        case IrBlendedNoise bn -> {
                            loadBinding(bn.constSlot, CD_BLENDED_NOISE);
                            cb.iload(this.lBx);
                            cb.aload(P_BY);
                            cb.iload(this.lBz);
                            cb.aload(cs.colSliceArrayLocal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "sampleBlendedNoiseColumn",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_BLENDED_NOISE, ConstantDescs.CD_int,
                                            CD_INT_ARR, ConstantDescs.CD_int, CD_DOUBLE_ARR, ConstantDescs.CD_int));
                        }
                        default -> {}
                    }
                }
                cb.goto_(donePre);
                cb.labelBinding(notConstCol);
                if (!this.hoistNodes.isEmpty()) {
                    cb.iload(this.lCanQuartHoist);
                    Label skipCellHoist = cb.newLabel();
                    cb.ifeq(skipCellHoist);
                    cb.aload(P_BX); cb.iconst_0(); cb.iaload(); cb.istore(this.lBx);
                    cb.iconst_0(); cb.istore(this.lBy);
                    cb.aload(P_BZ); cb.iconst_0(); cb.iaload(); cb.istore(this.lBz);
                    for (IrNode h : this.hoistNodes) {
                        if (h.quartInvariant) {
                            emitRawNode(h, false);
                            cb.dstore(h.hoistedLocal);
                            if (h.cseValueLocal >= 0) {
                                cb.dload(h.hoistedLocal);
                                cb.dstore(h.cseValueLocal);
                            }
                        }
                    }
                    cb.labelBinding(skipCellHoist);
                }
                if (!this.colSliceNodes.isEmpty()) {
                    cb.iload(P_COUNT);
                    cb.iconst_4();
                    cb.if_icmplt(donePre);
                    for (int slot = 0; slot < this.colSliceNodes.size(); slot++) {
                        IrNode cs = this.colSliceNodes.get(slot);
                        if (cs instanceof IrNoise n) {
                            cb.loadConstant(slot);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "borrowColScratch",
                                    MethodTypeDesc.of(CD_DOUBLE_ARR, ConstantDescs.CD_int, ConstantDescs.CD_int));
                            cb.astore(cs.colSliceArrayLocal);
                            loadBinding(n.noiseConstSlot, CD_NORMAL_NOISE);
                            cb.aload(P_BX);
                            cb.aload(P_BY);
                            cb.aload(P_BZ);
                            cb.loadConstant(n.xzScale);
                            cb.loadConstant(n.yScale);
                            cb.aload(cs.colSliceArrayLocal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "sampleNormalNoiseBatchInt",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE,
                                            CD_INT_ARR, CD_INT_ARR, CD_INT_ARR,
                                            ConstantDescs.CD_double, ConstantDescs.CD_double,
                                            CD_DOUBLE_ARR, ConstantDescs.CD_int));
                        } else if (cs instanceof IrShift sh) {
                            cb.loadConstant(slot);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "borrowColScratch",
                                    MethodTypeDesc.of(CD_DOUBLE_ARR, ConstantDescs.CD_int, ConstantDescs.CD_int));
                            cb.astore(cs.colSliceArrayLocal);
                            loadBinding(sh.noiseConstSlot, CD_NORMAL_NOISE);
                            cb.aload(P_BX);
                            cb.aload(P_BY);
                            cb.aload(P_BZ);
                            cb.aload(cs.colSliceArrayLocal);
                            cb.iload(P_COUNT);
                            cb.invokestatic(CD_SUPPORT, "sampleShiftBatchInt",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE,
                                            CD_INT_ARR, CD_INT_ARR, CD_INT_ARR,
                                            CD_DOUBLE_ARR, ConstantDescs.CD_int));
                        }
                    }
                }
                cb.labelBinding(donePre);
            }

            // Main voxel batch loop
            cb.iconst_0();
            cb.istore(this.lIdx);
            Label loopTop = cb.newLabel();
            Label loopEnd = cb.newLabel();
            cb.labelBinding(loopTop);
            cb.iload(this.lIdx);
            cb.iload(P_COUNT);
            cb.if_icmpge(loopEnd);

            // Reset conditional CSE flags
            for (IrNode cn : this.condCseNodes) {
                cb.iconst_0();
                cb.istore(cn.cseComputedFlagLocal);
            }

            // Load current (bx, by, bz)
            cb.aload(P_BX); cb.iload(this.lIdx); cb.iaload(); cb.istore(this.lBx);
            cb.aload(P_BY); cb.iload(this.lIdx); cb.iaload(); cb.istore(this.lBy);
            cb.aload(P_BZ); cb.iload(this.lIdx); cb.iaload(); cb.istore(this.lBz);
            if (this.interpCount > 0) {
                cb.iload(this.lCanCellLerp);
                Label skipVoxelFrac = cb.newLabel();
                cb.ifeq(skipVoxelFrac);
                cb.iload(this.lUseCellArr);
                cb.ifne(skipVoxelFrac);
                cb.iload(this.lBx); cb.iload(this.lCellStartX); cb.isub(); cb.i2d(); cb.dload(this.lInvCw); cb.dmul(); cb.dstore(this.lTx);
                cb.iload(this.lBy); cb.iload(this.lCellStartY); cb.isub(); cb.i2d(); cb.dload(this.lInvCh); cb.dmul(); cb.dstore(this.lTy);
                cb.iload(this.lBz); cb.iload(this.lCellStartZ); cb.isub(); cb.i2d(); cb.dload(this.lInvCw); cb.dmul(); cb.dstore(this.lTz);
                cb.labelBinding(skipVoxelFrac);
            }

            // Precompute unconditional shared CSE nodes once for this voxel in topological order
            for (IrNode n : this.topoOrder) {
                if (n.cseValueLocal >= 0 && n.unconditional) {
                    emitNodeWithHoistCheck(n);
                    cb.dstore(n.cseValueLocal);
                }
            }

            // Evaluate root and write out[i]
            cb.aload(P_OUT);
            cb.iload(this.lIdx);
            emitUseNode(this.root);
            cb.dastore();

            cb.iinc(this.lIdx, 1);
            cb.goto_(loopTop);
            cb.labelBinding(loopEnd);
            cb.return_();
        }

        private static boolean isExpensiveLeaf(IrNode n) {
            return n instanceof IrFlatCache || n instanceof IrCache2D
                    || n instanceof IrNoise || n instanceof IrShiftedNoise || n instanceof IrShift;
        }

        private void emitUseNode(IrNode n) {
            if (n.cseValueLocal >= 0) {
                if (n.unconditional) {
                    cb.dload(n.cseValueLocal);
                    return;
                }
                // Conditional CSE node: compute lazily on first encounter in iteration
                cb.iload(n.cseComputedFlagLocal);
                Label alreadyComputed = cb.newLabel();
                cb.ifne(alreadyComputed);
                emitNodeWithHoistCheck(n);
                cb.dstore(n.cseValueLocal);
                cb.iconst_1();
                cb.istore(n.cseComputedFlagLocal);
                cb.labelBinding(alreadyComputed);
                cb.dload(n.cseValueLocal);
                return;
            }
            emitNodeWithHoistCheck(n);
        }

        private void emitNodeWithHoistCheck(IrNode n) {
            if (n.colSliceArrayLocal >= 0) {
                if (n.unconditional) {
                    cb.aload(n.colSliceArrayLocal);
                    Label notCol = cb.newLabel();
                    Label done = cb.newLabel();
                    cb.ifnull(notCol);
                    cb.aload(n.colSliceArrayLocal);
                    cb.iload(this.lIdx);
                    cb.daload();
                    cb.goto_(done);
                    cb.labelBinding(notCol);
                    emitRawNode(n, true);
                    cb.labelBinding(done);
                    return;
                }
                cb.iload(P_CONST_Y_COL);
                Label notCol = cb.newLabel();
                Label haveArr = cb.newLabel();
                Label done = cb.newLabel();
                cb.ifeq(notCol);
                cb.aload(n.colSliceArrayLocal);
                cb.ifnonnull(haveArr);
                cb.loadConstant(n.colSliceSlot);
                cb.iload(P_COUNT);
                cb.invokestatic(CD_SUPPORT, "borrowColScratch",
                        MethodTypeDesc.of(CD_DOUBLE_ARR, ConstantDescs.CD_int, ConstantDescs.CD_int));
                cb.astore(n.colSliceArrayLocal);
                switch (n) {
                    case IrNoise nn -> {
                        loadBinding(nn.noiseConstSlot, CD_NORMAL_NOISE);
                        cb.iload(this.lBx); cb.i2d(); cb.loadConstant(nn.xzScale); cb.dmul();
                        cb.aload(P_BY);
                        cb.iload(this.lIdx);
                        cb.loadConstant(nn.yScale);
                        cb.iload(this.lBz); cb.i2d(); cb.loadConstant(nn.xzScale); cb.dmul();
                        cb.aload(n.colSliceArrayLocal);
                        cb.iload(P_COUNT);
                        cb.invokestatic(CD_SUPPORT, "sampleNormalNoiseColumnRange",
                                MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE, ConstantDescs.CD_double,
                                        CD_INT_ARR, ConstantDescs.CD_int, ConstantDescs.CD_double, ConstantDescs.CD_double,
                                        CD_DOUBLE_ARR, ConstantDescs.CD_int));
                    }
                    case IrShiftedNoise sn -> {
                        loadBinding(sn.noiseConstSlot, CD_NORMAL_NOISE);
                        if (sn.scaleAfterShift) {
                            cb.iload(this.lBx); cb.i2d(); emitUseNode(sn.sx); cb.dadd(); cb.loadConstant(sn.xzScale); cb.dmul();
                        } else {
                            cb.iload(this.lBx); cb.i2d(); cb.loadConstant(sn.xzScale); cb.dmul(); emitUseNode(sn.sx); cb.dadd();
                        }
                        cb.aload(P_BY);
                        cb.iload(this.lIdx);
                        cb.loadConstant(sn.yScale);
                        if (sn.scaleAfterShift) {
                            cb.iload(this.lBz); cb.i2d(); emitUseNode(sn.sz); cb.dadd(); cb.loadConstant(sn.xzScale); cb.dmul();
                        } else {
                            cb.iload(this.lBz); cb.i2d(); cb.loadConstant(sn.xzScale); cb.dmul(); emitUseNode(sn.sz); cb.dadd();
                        }
                        cb.aload(n.colSliceArrayLocal);
                        cb.iload(P_COUNT);
                        cb.invokestatic(CD_SUPPORT, "sampleNormalNoiseColumnRange",
                                MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE, ConstantDescs.CD_double,
                                        CD_INT_ARR, ConstantDescs.CD_int, ConstantDescs.CD_double, ConstantDescs.CD_double,
                                        CD_DOUBLE_ARR, ConstantDescs.CD_int));
                    }
                    case IrShift sh -> {
                        loadBinding(sh.noiseConstSlot, CD_NORMAL_NOISE);
                        cb.iload(this.lBx); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.aload(P_BY);
                        cb.iload(this.lIdx);
                        cb.iload(this.lBz); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.aload(n.colSliceArrayLocal);
                        cb.iload(P_COUNT);
                        cb.invokestatic(CD_SUPPORT, "sampleShiftColumnRange",
                                MethodTypeDesc.of(ConstantDescs.CD_void, CD_NORMAL_NOISE, ConstantDescs.CD_double,
                                        CD_INT_ARR, ConstantDescs.CD_int, ConstantDescs.CD_double,
                                        CD_DOUBLE_ARR, ConstantDescs.CD_int));
                    }
                    case IrBlendedNoise bn -> {
                        loadBinding(bn.constSlot, CD_BLENDED_NOISE);
                        cb.iload(this.lBx);
                        cb.aload(P_BY);
                        cb.iload(this.lIdx);
                        cb.iload(this.lBz);
                        cb.aload(n.colSliceArrayLocal);
                        cb.iload(P_COUNT);
                        cb.invokestatic(CD_SUPPORT, "sampleBlendedNoiseColumnRange",
                                MethodTypeDesc.of(ConstantDescs.CD_void, CD_BLENDED_NOISE, ConstantDescs.CD_int,
                                        CD_INT_ARR, ConstantDescs.CD_int, ConstantDescs.CD_int,
                                        CD_DOUBLE_ARR, ConstantDescs.CD_int));
                    }
                    default -> {}
                }
                cb.labelBinding(haveArr);
                cb.aload(n.colSliceArrayLocal);
                cb.iload(this.lIdx);
                cb.daload();
                cb.goto_(done);
                cb.labelBinding(notCol);
                emitRawNode(n, true);
                cb.labelBinding(done);
                return;
            }
            if (n.hoistedLocal >= 0) {
                cb.iload(P_CONST_Y_COL);
                if (n.quartInvariant) {
                    cb.iload(this.lCanQuartHoist);
                    cb.ior();
                }
                Label notHoisted = cb.newLabel();
                Label done = cb.newLabel();
                cb.ifeq(notHoisted);
                cb.dload(n.hoistedLocal);
                cb.goto_(done);
                cb.labelBinding(notHoisted);
                emitRawNode(n, true);
                cb.labelBinding(done);
                return;
            }
            emitRawNode(n, true);
        }

        private void emitRawNode(IrNode node, boolean allowCseChildren) {
            switch (node) {
                case IrConst c -> cb.loadConstant(c.val);
                case IrYClampedGradient yg -> {
                    cb.iload(this.lBy);
                    cb.i2d();
                    cb.loadConstant((double) yg.fromY);
                    cb.loadConstant((double) yg.toY);
                    cb.loadConstant(yg.fromVal);
                    cb.loadConstant(yg.toVal);
                    cb.invokestatic(CD_MTH, "clampedMap",
                            MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double,
                                    ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                }
                case IrClamp cl -> {
                    emitChild(cl.in, allowCseChildren);
                    cb.loadConstant(cl.min);
                    cb.loadConstant(cl.max);
                    cb.invokestatic(CD_MTH, "clamp",
                            MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                }
                case IrMapped m -> {
                    emitChild(m.in, allowCseChildren);
                    emitMappedOp(m.typeOrd);
                }
                case IrMulOrAdd ma -> {
                    emitChild(ma.in, allowCseChildren);
                    cb.loadConstant(ma.arg);
                    if (ma.typeOrd == 0) cb.dmul();
                    else cb.dadd();
                }
                case IrAp2 ap -> emitAp2(ap, allowCseChildren);
                case IrRangeChoice rc -> emitRangeChoice(rc, allowCseChildren);
                case IrIntervalSelect is -> emitIntervalSelect(is, allowCseChildren);
                case IrNoise n -> {
                    loadBinding(n.noiseConstSlot, CD_NORMAL_NOISE);
                    cb.iload(this.lBx); cb.i2d(); cb.loadConstant(n.xzScale); cb.dmul();
                    if (n.yScale == 0.0D) {
                        cb.dconst_0();
                    } else {
                        cb.iload(this.lBy); cb.i2d(); cb.loadConstant(n.yScale); cb.dmul();
                    }
                    cb.iload(this.lBz); cb.i2d(); cb.loadConstant(n.xzScale); cb.dmul();
                    cb.invokestatic(CD_SUPPORT, "sampleNormalNoise",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_NORMAL_NOISE, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                }
                case IrShiftedNoise sn -> {
                    loadBinding(sn.noiseConstSlot, CD_NORMAL_NOISE);
                    if (sn.scaleAfterShift) {
                        cb.iload(this.lBx); cb.i2d();
                        emitChild(sn.sx, allowCseChildren); cb.dadd();
                        cb.loadConstant(sn.xzScale); cb.dmul();
                    } else {
                        cb.iload(this.lBx); cb.i2d(); cb.loadConstant(sn.xzScale); cb.dmul();
                        emitChild(sn.sx, allowCseChildren); cb.dadd();
                    }
                    if (sn.yScale == 0.0D) {
                        cb.dconst_0();
                    } else {
                        cb.iload(this.lBy); cb.i2d(); cb.loadConstant(sn.yScale); cb.dmul();
                    }
                    emitChild(sn.sy, allowCseChildren); cb.dadd();
                    if (sn.scaleAfterShift) {
                        cb.iload(this.lBz); cb.i2d();
                        emitChild(sn.sz, allowCseChildren); cb.dadd();
                        cb.loadConstant(sn.xzScale); cb.dmul();
                    } else {
                        cb.iload(this.lBz); cb.i2d(); cb.loadConstant(sn.xzScale); cb.dmul();
                        emitChild(sn.sz, allowCseChildren); cb.dadd();
                    }
                    cb.invokestatic(CD_SUPPORT, "sampleNormalNoise",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_NORMAL_NOISE, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                }
                case IrShift sh -> {
                    loadBinding(sh.noiseConstSlot, CD_NORMAL_NOISE);
                    if (sh.kind == 0) { // ShiftA: (x*0.25, 0, z*0.25)
                        cb.iload(this.lBx); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.dconst_0();
                        cb.iload(this.lBz); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                    } else if (sh.kind == 1) { // ShiftB: (z*0.25, x*0.25, 0)
                        cb.iload(this.lBz); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.iload(this.lBx); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.dconst_0();
                    } else { // Shift: (x*0.25, y*0.25, z*0.25)
                        cb.iload(this.lBx); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.iload(this.lBy); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                        cb.iload(this.lBz); cb.i2d(); cb.loadConstant(0.25D); cb.dmul();
                    }
                    cb.invokestatic(CD_SUPPORT, "sampleNormalNoise",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_NORMAL_NOISE, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                    cb.loadConstant(4.0D);
                    cb.dmul();
                }
                case IrBlendedNoise bn -> {
                    loadBinding(bn.constSlot, CD_BLENDED_NOISE);
                    cb.iload(this.lBx); cb.iload(this.lBy); cb.iload(this.lBz);
                    cb.invokestatic(CD_SUPPORT, "sampleBlendedNoise",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_BLENDED_NOISE, ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
                }
                case IrFlatCache fc -> {
                    loadBinding(fc.bindingSlot, CD_OBJECT);
                    cb.iload(this.lBx); cb.iload(this.lBz);
                    cb.invokestatic(CD_SUPPORT, "sampleFlatCache",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_OBJECT, ConstantDescs.CD_int, ConstantDescs.CD_int));
                }
                case IrCache2D c2 -> {
                    loadBinding(c2.bindingSlot, CD_OBJECT);
                    cb.iload(this.lBx); cb.iload(this.lBz);
                    cb.invokestatic(CD_SUPPORT, "sampleCache2D",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_OBJECT, ConstantDescs.CD_int, ConstantDescs.CD_int));
                }
                case IrNoiseInterpolator ni -> {
                    Label notPreFilled = cb.newLabel();
                    Label slowPath = cb.newLabel();
                    Label done = cb.newLabel();
                    cb.aload(this.lInterpCellArr[ni.interpOrdinal]);
                    cb.ifnull(notPreFilled);
                    cb.aload(this.lInterpCellArr[ni.interpOrdinal]);
                    cb.iload(this.lIdx);
                    cb.daload();
                    cb.goto_(done);
                    cb.labelBinding(notPreFilled);
                    cb.iload(this.lCanCellLerp);
                    cb.ifeq(slowPath);
                    cb.dload(this.lTx);
                    cb.dload(this.lTy);
                    cb.dload(this.lTz);
                    int base = ni.interpOrdinal * 8;
                    for (int c = 0; c < 8; c++) {
                        cb.dload(this.lInterpCorners[base + c]);
                    }
                    cb.invokestatic(CD_SUPPORT, "sampleCornerLerp", MethodTypeDesc.of(
                            ConstantDescs.CD_double,
                            ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double,
                            ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double,
                            ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                    cb.goto_(done);
                    cb.labelBinding(slowPath);
                    loadBinding(ni.bindingSlot, CD_OBJECT);
                    cb.aload(P_NOISE_CHUNK);
                    cb.iload(this.lBx); cb.iload(this.lBy); cb.iload(this.lBz);
                    cb.invokestatic(CD_SUPPORT, "sampleInterpolatorPoint",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_OBJECT, CD_NOISE_CHUNK,
                                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
                    cb.labelBinding(done);
                }
                case IrBlendDensity bd -> {
                    emitChild(bd.inner, allowCseChildren);
                    cb.iload(this.lBlenderEmpty);
                    Label done = cb.newLabel();
                    cb.ifne(done);
                    int tmp = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(tmp);
                    cb.aload(P_NOISE_CHUNK);
                    cb.iload(this.lBx); cb.iload(this.lBy); cb.iload(this.lBz);
                    cb.dload(tmp);
                    cb.invokestatic(CD_SUPPORT, "blendDensity",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_NOISE_CHUNK,
                                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_double));
                    cb.labelBinding(done);
                }
                case IrBlendMarker bm -> {
                    cb.iload(this.lBlenderEmpty);
                    Label doSlow = cb.newLabel();
                    Label done = cb.newLabel();
                    cb.ifeq(doSlow);
                    if (bm.isAlpha) cb.dconst_1(); else cb.dconst_0();
                    cb.goto_(done);
                    cb.labelBinding(doSlow);
                    cb.aload(P_NOISE_CHUNK);
                    cb.iload(this.lBx); cb.iload(this.lBy); cb.iload(this.lBz);
                    loadBinding(bm.bindingSlot, CD_DF);
                    cb.invokestatic(CD_SUPPORT, bm.isAlpha ? "blendAlpha" : "blendOffset",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_NOISE_CHUNK,
                                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int, CD_DF));
                    cb.labelBinding(done);
                }
                case IrInlineSpline isp -> emitInlineSpline(isp.spline, allowCseChildren);
                case IrFlatSpline fsp -> {
                    int[] coordLocals = new int[fsp.coords.length];
                    for (int i = 0; i < fsp.coords.length; i++) {
                        emitChild(fsp.coords[i], allowCseChildren);
                        cb.d2f();
                        int loc = cb.allocateLocal(TypeKind.FLOAT);
                        cb.fstore(loc);
                        coordLocals[i] = loc;
                    }
                    for (int i = 0; i < fsp.coords.length; i++) {
                        cb.aload(this.lSplineFloatScratch);
                        cb.loadConstant(i);
                        cb.fload(coordLocals[i]);
                        cb.fastore();
                    }
                    loadBinding(fsp.flatConstSlot, CD_FLAT_SPLINE);
                    cb.aload(this.lSplineFloatScratch);
                    cb.invokestatic(CD_SUPPORT, "evalFlatSpline",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_FLAT_SPLINE, CD_FLOAT_ARR));
                }
                case IrBindingFallback fb -> {
                    loadBinding(fb.bindingSlot, CD_DF);
                    cb.iload(this.lBx); cb.iload(this.lBy); cb.iload(this.lBz);
                    cb.invokestatic(CD_SUPPORT, "evalFallback",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_DF,
                                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
                }
                case IrConstFallback cf -> {
                    loadConst(cf.constSlot, CD_DF);
                    cb.iload(this.lBx); cb.iload(this.lBy); cb.iload(this.lBz);
                    cb.invokestatic(CD_SUPPORT, "evalFallback",
                            MethodTypeDesc.of(ConstantDescs.CD_double, CD_DF,
                                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
                }
                default -> cb.dconst_0();
            }
        }

        private void emitChild(IrNode child, boolean allowCse) {
            if (allowCse) {
                emitUseNode(child);
            } else {
                // Pre-loop hoisted evaluation: also allow reading any already-hoisted child
                if (child.hoistedLocal >= 0 && child != this.root) {
                    cb.dload(child.hoistedLocal);
                } else {
                    emitRawNode(child, false);
                }
            }
        }

        private void emitMappedOp(int typeOrd) {
            switch (typeOrd) {
                case 0 -> cb.invokestatic(CD_MATH, "abs", MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double)); // ABS
                case 1 -> { // SQUARE
                    cb.dup2();
                    cb.dmul();
                }
                case 2 -> { // CUBE
                    int v = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(v);
                    cb.dload(v); cb.dload(v); cb.dmul(); cb.dload(v); cb.dmul();
                }
                case 3 -> emitNegScale(0.5D); // HALF_NEGATIVE
                case 4 -> emitNegScale(0.25D); // QUARTER_NEGATIVE
                case 5 -> { // INVERT: 1.0 / v
                    int v = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(v);
                    cb.dconst_1();
                    cb.dload(v);
                    cb.ddiv();
                }
                case 6 -> { // SQUEEZE: c = clamp(v, -1, 1); return c/2 - c^3/24
                    cb.loadConstant(-1.0D);
                    cb.dconst_1();
                    cb.invokestatic(CD_MTH, "clamp",
                            MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                    int c = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(c);
                    cb.dload(c); cb.loadConstant(0.5D); cb.dmul();
                    cb.dload(c); cb.dload(c); cb.dmul(); cb.dload(c); cb.dmul(); cb.loadConstant(24.0D); cb.ddiv();
                    cb.dsub();
                }
                default -> {}
            }
        }

        private void emitNegScale(double scale) {
            int v = cb.allocateLocal(TypeKind.DOUBLE);
            cb.dstore(v);
            cb.dload(v);
            cb.dconst_0();
            cb.dcmpl();
            Label pos = cb.newLabel();
            Label done = cb.newLabel();
            cb.ifgt(pos);
            cb.dload(v);
            cb.loadConstant(scale);
            cb.dmul();
            cb.goto_(done);
            cb.labelBinding(pos);
            cb.dload(v);
            cb.labelBinding(done);
        }

        private void emitAp2(IrAp2 ap, boolean allowCse) {
            emitChild(ap.a, allowCse);
            switch (ap.typeOrd) {
                case 0 -> { // ADD
                    emitChild(ap.b, allowCse);
                    cb.dadd();
                }
                case 1 -> { // MUL with 0.0 short-circuit matching DensityFunctions$Ap2
                    int aLoc = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(aLoc);
                    cb.dload(aLoc);
                    cb.dconst_0();
                    cb.dcmpl();
                    Label nonZero = cb.newLabel();
                    Label done = cb.newLabel();
                    cb.ifne(nonZero);
                    cb.dconst_0();
                    cb.goto_(done);
                    cb.labelBinding(nonZero);
                    cb.dload(aLoc);
                    emitChild(ap.b, allowCse);
                    cb.dmul();
                    cb.labelBinding(done);
                }
                case 2 -> { // MIN with bMinValue short-circuit
                    int aLoc = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(aLoc);
                    cb.dload(aLoc);
                    cb.loadConstant(ap.bMin);
                    cb.dcmpg();
                    Label evalB = cb.newLabel();
                    Label done = cb.newLabel();
                    cb.ifge(evalB);
                    cb.dload(aLoc);
                    cb.goto_(done);
                    cb.labelBinding(evalB);
                    cb.dload(aLoc);
                    emitChild(ap.b, allowCse);
                    cb.invokestatic(CD_MATH, "min",
                            MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                    cb.labelBinding(done);
                }
                case 3 -> { // MAX with bMaxValue short-circuit
                    int aLoc = cb.allocateLocal(TypeKind.DOUBLE);
                    cb.dstore(aLoc);
                    cb.dload(aLoc);
                    cb.loadConstant(ap.bMax);
                    cb.dcmpl();
                    Label evalB = cb.newLabel();
                    Label done = cb.newLabel();
                    cb.ifle(evalB);
                    cb.dload(aLoc);
                    cb.goto_(done);
                    cb.labelBinding(evalB);
                    cb.dload(aLoc);
                    emitChild(ap.b, allowCse);
                    cb.invokestatic(CD_MATH, "max",
                            MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double, ConstantDescs.CD_double));
                    cb.labelBinding(done);
                }
                default -> {
                    emitChild(ap.b, allowCse);
                    cb.dadd();
                }
            }
        }

        private void emitRangeChoice(IrRangeChoice rc, boolean allowCse) {
            int inLoc = cb.allocateLocal(TypeKind.DOUBLE);
            emitChild(rc.in, allowCse);
            cb.dstore(inLoc);
            Label outRange = cb.newLabel();
            Label done = cb.newLabel();
            cb.dload(inLoc);
            cb.loadConstant(rc.minInc);
            cb.dcmpl();
            cb.iflt(outRange);
            cb.dload(inLoc);
            cb.loadConstant(rc.maxExc);
            cb.dcmpg();
            cb.ifge(outRange);
            emitChild(rc.inRange, allowCse);
            cb.goto_(done);
            cb.labelBinding(outRange);
            emitChild(rc.outRange, allowCse);
            cb.labelBinding(done);
        }

        private void emitIntervalSelect(IrIntervalSelect is, boolean allowCse) {
            int inLoc = cb.allocateLocal(TypeKind.DOUBLE);
            emitChild(is.in, allowCse);
            cb.dstore(inLoc);
            Label done = cb.newLabel();
            for (int i = 0; i < is.thresholds.length; i++) {
                Label next = cb.newLabel();
                cb.dload(inLoc);
                cb.loadConstant(is.thresholds[i]);
                cb.dcmpg();
                cb.ifge(next);
                emitChild(is.branches[i], allowCse);
                cb.goto_(done);
                cb.labelBinding(next);
            }
            emitChild(is.branches[is.branches.length - 1], allowCse);
            cb.labelBinding(done);
        }

        private void emitInlineSpline(InlineSpline sp, boolean allowCse) {
            emitInlineSplineFloat(sp, allowCse);
            cb.f2d();
        }

        private void emitInlineSplineFloat(InlineSpline sp, boolean allowCse) {
            if (sp instanceof InlineSplineConst c) {
                cb.loadConstant(c.val);
                return;
            }
            InlineSplineMulti m = (InlineSplineMulti) sp;
            int coordLoc = cb.allocateLocal(TypeKind.FLOAT);
            emitChild(m.coord, allowCse);
            cb.d2f();
            cb.fstore(coordLoc);

            float[] locs = m.locs;
            float[] derivs = m.derivs;
            int last = locs.length - 1;
            Label done = cb.newLabel();

            // Check c < locs[0]
            Label checkIntervals = cb.newLabel();
            cb.fload(coordLoc);
            cb.loadConstant(locs[0]);
            cb.fcmpg();
            cb.ifge(checkIntervals);
            emitInlineSplineFloat(m.children[0], allowCse);
            emitLinearExtend(coordLoc, locs[0], derivs[0]);
            cb.goto_(done);

            cb.labelBinding(checkIntervals);
            for (int k = 0; k < last; k++) {
                Label nextInt = cb.newLabel();
                cb.fload(coordLoc);
                cb.loadConstant(locs[k + 1]);
                cb.fcmpg();
                cb.ifge(nextInt);

                // Hermite interpolation on [locs[k], locs[k+1]]
                int fLoc = cb.allocateLocal(TypeKind.FLOAT);
                int gLoc = cb.allocateLocal(TypeKind.FLOAT);
                int tLoc = cb.allocateLocal(TypeKind.FLOAT);
                emitInlineSplineFloat(m.children[k], allowCse);
                cb.fstore(fLoc);
                emitInlineSplineFloat(m.children[k + 1], allowCse);
                cb.fstore(gLoc);

                float l0 = locs[k];
                float dx = locs[k + 1] - l0;
                cb.fload(coordLoc);
                cb.loadConstant(l0);
                cb.fsub();
                cb.loadConstant(dx);
                cb.fdiv();
                cb.fstore(tLoc);

                // diff = g - f; a = derivs[k]*dx - diff; b = -derivs[k+1]*dx + diff
                // res = Mth.lerp(t, f, g) + t * (1 - t) * Mth.lerp(t, a, b)
                int aLoc = cb.allocateLocal(TypeKind.FLOAT);
                int bLoc = cb.allocateLocal(TypeKind.FLOAT);
                cb.loadConstant(derivs[k] * dx);
                cb.fload(gLoc); cb.fload(fLoc); cb.fsub();
                cb.fsub();
                cb.fstore(aLoc);

                cb.loadConstant(-derivs[k + 1] * dx);
                cb.fload(gLoc); cb.fload(fLoc); cb.fsub();
                cb.fadd();
                cb.fstore(bLoc);

                // lerp(t, f, g)
                cb.fload(tLoc); cb.fload(fLoc); cb.fload(gLoc);
                cb.invokestatic(CD_MTH, "lerp", MethodTypeDesc.of(ConstantDescs.CD_float, ConstantDescs.CD_float, ConstantDescs.CD_float, ConstantDescs.CD_float));
                // t * (1 - t) * lerp(t, a, b)
                cb.fload(tLoc);
                cb.fconst_1(); cb.fload(tLoc); cb.fsub();
                cb.fmul();
                cb.fload(tLoc); cb.fload(aLoc); cb.fload(bLoc);
                cb.invokestatic(CD_MTH, "lerp", MethodTypeDesc.of(ConstantDescs.CD_float, ConstantDescs.CD_float, ConstantDescs.CD_float, ConstantDescs.CD_float));
                cb.fmul();
                cb.fadd();
                cb.goto_(done);
                cb.labelBinding(nextInt);
            }

            // c >= locs[last]
            emitInlineSplineFloat(m.children[last], allowCse);
            emitLinearExtend(coordLoc, locs[last], derivs[last]);
            cb.labelBinding(done);
        }

        private void emitLinearExtend(int coordLoc, float locVal, float deriv) {
            if (deriv == 0.0F) {
                return;
            }
            cb.loadConstant(deriv);
            cb.fload(coordLoc);
            cb.loadConstant(locVal);
            cb.fsub();
            cb.fmul();
            cb.fadd();
        }

        private void loadConst(int slot, ClassDesc type) {
            cb.aload(this.lConsts);
            cb.loadConstant(slot);
            cb.aaload();
            cb.checkcast(type);
        }

        private void loadBinding(int slot, ClassDesc type) {
            cb.aload(P_BINDINGS);
            cb.loadConstant(slot);
            cb.aaload();
            if (!CD_OBJECT.equals(type)) {
                cb.checkcast(type);
            }
        }
    }

        // Compile-Time Lock-Free Property Accessors
        private static Object invokeObj(Object target, String name) {
        MethodHandle mh = PROPERTY_GETTERS.get(target.getClass()).get(name);
        if (mh == null) {
            throw new IllegalStateException("Missing property " + name + " on " + target.getClass().getName());
        }
        try {
            return mh.invokeExact(target);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed reading property " + name + " on " + target.getClass().getName(), t);
        }
    }

    private static double invokeDouble(Object target, String name) {
        return ((Number) invokeObj(target, name)).doubleValue();
    }

    private static int invokeInt(Object target, String name) {
        return ((Number) invokeObj(target, name)).intValue();
    }
}
