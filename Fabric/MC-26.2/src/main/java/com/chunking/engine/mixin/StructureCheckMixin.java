package com.chunking.engine.mixin;

import com.chunking.engine.access.RegionStorageAccess;
import com.chunking.engine.world.StructureCache.StripedFeatureMap;
import com.chunking.engine.world.StructureCache.StripedLoadedMap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2BooleanFunction;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Striped concurrent tables for {@link StructureCheck} replacing the coarse global {@code loadedChunks}
 * lock and per-structure {@code featureChecks} locks while preserving bounded eviction and lock-free
 * reads on published per-chunk structure count maps.
 */
@Mixin(StructureCheck.class)
public abstract class StructureCheckMixin {
    @Shadow
    @Final
    private ChunkScanAccess storageAccess;

    @Shadow
    @Final
    @Mutable
    private Long2ObjectMap<Object2IntMap<Structure>> loadedChunks;

    @Shadow
    @Final
    @Mutable
    private Map<Structure, Long2BooleanMap> featureChecks;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initStripedMaps(CallbackInfo ci) {
        this.loadedChunks = new StripedLoadedMap();
        this.featureChecks = new ConcurrentHashMap<>(32);
    }

    @WrapOperation(
            method = "checkStart",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"
            )
    )
    private Object chunking$useStripedFeatureMap(
            Map<Structure, Long2BooleanMap> map,
            Object key,
            Function<Structure, Long2BooleanMap> mappingFunction,
            Operation<Object> original
    ) {
        if (map == this.featureChecks) {
            Structure structure = (Structure) key;
            Long2BooleanMap existing = map.get(structure);
            if (existing != null) {
                return existing;
            }
            Long2BooleanMap created = new StripedFeatureMap();
            Long2BooleanMap prev = map.putIfAbsent(structure, created);
            return prev != null ? prev : created;
        }
        return original.call(map, key, mappingFunction);
    }

    @Redirect(
            method = "storeFullResults(JLit/unimi/dsi/fastutil/objects/Object2IntMap;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"
            )
    )
    private void chunking$cleanupFeatureChecks(
            Collection<Long2BooleanMap> values,
            Consumer<Long2BooleanMap> consumer
    ) {
        for (Long2BooleanMap map : this.featureChecks.values()) {
            if (map instanceof StripedFeatureMap) {
                consumer.accept(map);
            } else {
                synchronized (map) {
                    consumer.accept(map);
                }
            }
        }
    }

    @Inject(method = "tryLoadFromStorage", at = @At("HEAD"), cancellable = true)
    private void chunking$skipStorageScanOnAbsentRegion(
            ChunkPos pos,
            Structure structure,
            boolean skipKnownStructures,
            long packedPos,
            CallbackInfoReturnable<StructureCheckResult> cir
    ) {
        if (this.storageAccess instanceof RegionStorageAccess access
                && !access.chunking$hasRegionFileOnDisk(pos.getRegionX(), pos.getRegionZ())) {
            cir.setReturnValue(null);
        }
    }

    @Redirect(
            method = "checkStart",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2BooleanMap;computeIfAbsent(JLit/unimi/dsi/fastutil/longs/Long2BooleanFunction;)Z"
            )
    )
    private boolean chunking$lockFreeFeatureEvaluate(
            Long2BooleanMap map,
            long posLong,
            Long2BooleanFunction mappingFunction
    ) {
        if (map instanceof StripedFeatureMap striped) {
            return striped.computeWithoutHoldingLock(posLong, mappingFunction);
        }
        synchronized (map) {
            if (map.containsKey(posLong)) {
                return map.get(posLong);
            }
        }
        boolean created = mappingFunction.get(posLong);
        synchronized (map) {
            if (map.size() > 65_536) {
                map.clear();
            }
            map.put(posLong, created);
        }
        return created;
    }

    /**
     * @author Chunking
     * @reason Stripe-locked copy-on-write reference increment so concurrent checkStart readers never lock.
     */
    @Overwrite
    public void incrementReference(ChunkPos pos, Structure structure) {
        long packed = pos.pack();
        if (this.loadedChunks instanceof StripedLoadedMap striped) {
            striped.incrementReference(packed, structure);
            return;
        }
        synchronized (this.loadedChunks) {
            Object2IntMap<Structure> prev = this.loadedChunks.get(packed);
            Object2IntOpenHashMap<Structure> next = (prev == null || prev.isEmpty())
                    ? new Object2IntOpenHashMap<>(2)
                    : new Object2IntOpenHashMap<>(prev);
            next.addTo(structure, 1);
            this.loadedChunks.put(packed, next);
        }
    }
}
