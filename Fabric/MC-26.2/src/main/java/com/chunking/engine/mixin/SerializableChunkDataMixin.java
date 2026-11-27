package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.chunking.engine.io.TickNbt;
import com.chunking.engine.io.SectionReader;
import com.chunking.engine.io.SectionWriter;
import com.chunking.engine.io.UnloadSaveCtx;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Bypasses DFU {@code Codec.encodeStart}/{@code Codec.parse} inside {@link SerializableChunkData}
 * for section {@code block_states}/{@code biomes} containers, {@code block_ticks}/{@code fluid_ticks}
 * {@code SavedTick} lists, and {@code Status}, while eliding defensive {@link LevelChunkSection#copy()}
 * during unloading chunk serialization.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
    @Unique
    private static final ThreadLocal<PalettedContainerFactory> CHUNKING$PARSE_FACTORY = new ThreadLocal<>();

    @SuppressWarnings("unchecked")
    @WrapOperation(
            method = "write()Lnet/minecraft/nbt/CompoundTag;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/CompoundTag;store(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"
            ),
            require = 0
    )
    private <T> void chunking$fastEncodeSectionContainers(
            CompoundTag instance,
            String key,
            Codec<T> codec,
            T value,
            Operation<Void> original
    ) {
        if (ModConfig.PARALLEL_STATUS_TASKS && value instanceof PalettedContainer<?> pc) {
            if ("block_states".equals(key)) {
                CompoundTag encoded = SectionWriter.encodeBlockStates((PalettedContainer<BlockState>) pc);
                if (encoded != null) {
                    instance.put(key, encoded);
                    return;
                }
            } else if ("biomes".equals(key)) {
                CompoundTag encoded = SectionWriter.encodeBiomes((PalettedContainer<Holder<Biome>>) pc);
                if (encoded != null) {
                    instance.put(key, encoded);
                    return;
                }
            }
        }
        original.call(instance, key, codec, value);
    }

    @WrapOperation(
            method = "saveTicks(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/chunk/ChunkAccess$PackedTicks;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/CompoundTag;store(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"
            ),
            require = 0
    )
    private static <T> void chunking$fastEncodeTicks(
            CompoundTag instance,
            String key,
            Codec<T> codec,
            T value,
            Operation<Void> original
    ) {
        if (value instanceof List<?> list) {
            if (ModConfig.PARALLEL_STATUS_TASKS) {
                ListTag fast = null;
                if ("block_ticks".equals(key)) {
                    fast = TickNbt.encodeBlockTicks(list);
                } else if ("fluid_ticks".equals(key)) {
                    fast = TickNbt.encodeFluidTicks(list);
                }
                if (fast != null) {
                    instance.put(key, fast);
                    return;
                }
            }
            if (list.isEmpty()) {
                instance.put(key, new ListTag());
                return;
            }
        }
        original.call(instance, key, codec, value);
    }

    @SuppressWarnings("unchecked")
    @WrapOperation(
            method = {
                    "parse(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;",
                    "getChunkStatusFromTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/status/ChunkStatus;"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/CompoundTag;read(Ljava/lang/String;Lcom/mojang/serialization/Codec;)Ljava/util/Optional;"
            ),
            require = 0
    )
    private static <T> Optional<T> chunking$fastReadParsedField(
            CompoundTag instance,
            String key,
            Codec<T> codec,
            Operation<Optional<T>> original
    ) {
        if (!ModConfig.PARALLEL_STATUS_TASKS) {
            return original.call(instance, key, codec);
        }
        if (!instance.contains(key)) {
            return Optional.empty();
        }
        if ("block_ticks".equals(key)) {
            Optional<?> fast = TickNbt.decodeBlockTicks(instance, key);
            if (fast != null) {
                return (Optional<T>) fast;
            }
        } else if ("fluid_ticks".equals(key)) {
            Optional<?> fast = TickNbt.decodeFluidTicks(instance, key);
            if (fast != null) {
                return (Optional<T>) fast;
            }
        } else if ("Status".equals(key)) {
            Optional<?> fast = TickNbt.decodeChunkStatus(instance, key);
            if (fast != null) {
                return (Optional<T>) fast;
            }
        }
        return original.call(instance, key, codec);
    }

    @WrapMethod(method = "parse(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/chunk/PalettedContainerFactory;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;")
    private static SerializableChunkData chunking$bindParseFactory(
            LevelHeightAccessor levelHeightAccessor,
            PalettedContainerFactory containerFactory,
            CompoundTag chunkData,
            Operation<SerializableChunkData> original
    ) {
        PalettedContainerFactory prev = CHUNKING$PARSE_FACTORY.get();
        CHUNKING$PARSE_FACTORY.set(containerFactory);
        try {
            return original.call(levelHeightAccessor, containerFactory, chunkData);
        } finally {
            if (prev != null) {
                CHUNKING$PARSE_FACTORY.set(prev);
            } else {
                CHUNKING$PARSE_FACTORY.remove();
            }
        }
    }

    @Dynamic
    @Inject(method = "lambda$parse$3", at = @At("HEAD"), cancellable = true, require = 0)
    private static void chunking$fastDecodeBlockStates(
            Codec<PalettedContainer<BlockState>> codec,
            ChunkPos pos,
            int sectionY,
            CompoundTag tag,
            CallbackInfoReturnable<PalettedContainer<BlockState>> cir
    ) {
        PalettedContainerFactory factory = CHUNKING$PARSE_FACTORY.get();
        if (factory != null && ModConfig.PARALLEL_STATUS_TASKS) {
            PalettedContainer<BlockState> fast = SectionReader.decodeBlockStates(
                    tag, factory.blockStatesStrategy(), false);
            if (fast != null) {
                cir.setReturnValue(fast);
            }
        }
    }

    @Dynamic
    @Inject(method = "lambda$parse$5", at = @At("HEAD"), cancellable = true, require = 0)
    private static void chunking$fastDecodeBiomes(
            Codec<PalettedContainerRO<Holder<Biome>>> codec,
            ChunkPos pos,
            int sectionY,
            CompoundTag tag,
            CallbackInfoReturnable<PalettedContainerRO<Holder<Biome>>> cir
    ) {
        PalettedContainerFactory factory = CHUNKING$PARSE_FACTORY.get();
        if (factory != null && ModConfig.PARALLEL_STATUS_TASKS) {
            PalettedContainerRO<Holder<Biome>> fast = SectionReader.decodeBiomes(
                    tag, factory.biomeStrategy(), false);
            if (fast != null) {
                cir.setReturnValue(fast);
            }
        }
    }

    @WrapOperation(
            method = "copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;copy()Lnet/minecraft/world/level/chunk/LevelChunkSection;"
            ),
            require = 0
    )
    private static LevelChunkSection chunking$skipSectionCopyOnUnload(
            LevelChunkSection section,
            Operation<LevelChunkSection> original
    ) {
        if (UnloadSaveCtx.isActive()) {
            return section;
        }
        return original.call(section);
    }
}
