package com.chunking.engine.mixin;

import com.chunking.engine.access.LightEngineAccess;
import com.chunking.engine.access.LightUpdatingAccess;
import com.chunking.engine.world.LongQueue;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Direct-mapped chunk/section caches and fast opacity/occlusion checks for LightEngine.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineCacheMixin implements LightEngineAccess {
    @Unique
    private static final int CHUNKING$CHUNK_CACHE_SIZE = 64;

    @Unique
    private static final int CHUNKING$CHUNK_CACHE_MASK = CHUNKING$CHUNK_CACHE_SIZE - 1;

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Shadow
    @Final
    protected LayerLightSectionStorage<?> storage;

    @Shadow
    @Final
    @Mutable
    private LongArrayFIFOQueue decreaseQueue;

    @Shadow
    @Final
    @Mutable
    private LongArrayFIFOQueue increaseQueue;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$installNonShrinkingQueues(CallbackInfo ci) {
        this.decreaseQueue = new LongQueue(4096);
        this.increaseQueue = new LongQueue(4096);
        long[] keys = new long[CHUNKING$CHUNK_CACHE_SIZE];
        Arrays.fill(keys, Long.MAX_VALUE);
        this.chunking$chunkKeys = keys;
        this.chunking$chunks = new LightChunk[CHUNKING$CHUNK_CACHE_SIZE];
        this.chunking$lastSecChunkX = Integer.MIN_VALUE;
        this.chunking$lastSecChunkZ = Integer.MIN_VALUE;
        this.chunking$lastSecIdx = Integer.MIN_VALUE;
    }

    @Unique
    private boolean chunking$inLightUpdate;

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "runLightUpdates")
    private int chunking$trackInLightUpdate(com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
        this.chunking$inLightUpdate = true;
        try {
            return original.call();
        } finally {
            this.chunking$inLightUpdate = false;
        }
    }

    @Override
    public boolean chunking$isInLightUpdate() {
        return this.chunking$inLightUpdate;
    }

    @Shadow
    protected abstract void enqueueIncrease(long pos, long entry);

    @Shadow
    protected abstract void enqueueDecrease(long pos, long entry);

    @Shadow
    protected abstract void propagateIncrease(long blockPos, long queueEntry, int fromLevel);

    @Shadow
    public static VoxelShape getOcclusionShape(BlockState state, Direction dir) {
        throw new AssertionError();
    }

    @Unique
    private long[] chunking$chunkKeys;

    @Unique
    private LightChunk[] chunking$chunks;

    @Unique
    private int chunking$lastSecChunkX;

    @Unique
    private int chunking$lastSecChunkZ;

    @Unique
    private int chunking$lastSecIdx;

    @Unique
    private LevelChunkSection chunking$lastSection;

    @Unique
    private ChunkAccess chunking$lastChunkAccess;

    @Unique
    private void chunking$ensureInit() {
        if (this.chunking$chunkKeys == null) {
            long[] keys = new long[CHUNKING$CHUNK_CACHE_SIZE];
            Arrays.fill(keys, Long.MAX_VALUE);
            this.chunking$chunkKeys = keys;
            this.chunking$chunks = new LightChunk[CHUNKING$CHUNK_CACHE_SIZE];
        }
    }

    @Inject(method = "clearChunkCache", at = @At("HEAD"))
    private void chunking$clearFastChunkCache(CallbackInfo ci) {
        if (this.chunking$chunkKeys != null) {
            Arrays.fill(this.chunking$chunkKeys, Long.MAX_VALUE);
            Arrays.fill(this.chunking$chunks, null);
        }
        this.chunking$lastSecChunkX = Integer.MIN_VALUE;
        this.chunking$lastSecChunkZ = Integer.MIN_VALUE;
        this.chunking$lastSecIdx = Integer.MIN_VALUE;
        this.chunking$lastSection = null;
        this.chunking$lastChunkAccess = null;
    }

    /**
     * @author Chunking
     * @reason 16-slot direct-mapped neighborhood chunk lookup without wrapper allocations.
     */
    @Overwrite
    protected LightChunk getChunk(int cx, int cz) {
        long packed = ChunkPos.pack(cx, cz);
        int slot = ((int) HashCommon.mix(packed)) & CHUNKING$CHUNK_CACHE_MASK;
        if (this.chunking$chunkKeys[slot] == packed) {
            return this.chunking$chunks[slot];
        }
        LightChunk chunk = this.chunkSource.getChunkForLighting(cx, cz);
        if (chunk != null) {
            this.chunking$chunkKeys[slot] = packed;
            this.chunking$chunks[slot] = chunk;
        }
        return chunk;
    }

    /**
     * @author Chunking
     * @reason Last-section LevelChunkSection fast path cutting PalettedContainer.get dispatch overhead during light propagation.
     */
    @Overwrite
    protected BlockState getState(BlockPos pos) {
        return chunking$getStateCoords(pos.getX(), pos.getY(), pos.getZ(), pos);
    }

    @Unique
    private BlockState chunking$getStateCoords(int x, int y, int z, BlockPos posFallback) {
        int cx = x >> 4;
        int cz = z >> 4;

        ChunkAccess lastCa = this.chunking$lastChunkAccess;
        if (lastCa != null && cx == this.chunking$lastSecChunkX && cz == this.chunking$lastSecChunkZ) {
            int secIdx = lastCa.getSectionIndex(y);
            if (secIdx == this.chunking$lastSecIdx) {
                LevelChunkSection sec = this.chunking$lastSection;
                if (sec != null) {
                    return sec.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
                }
            } else {
                LevelChunkSection[] secs = lastCa.getSections();
                if (secIdx >= 0 && secIdx < secs.length) {
                    LevelChunkSection sec = secs[secIdx];
                    this.chunking$lastSecIdx = secIdx;
                    this.chunking$lastSection = sec;
                    return (sec == null || sec.hasOnlyAir()) ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
                }
                return (lastCa instanceof ProtoChunk && !(lastCa instanceof ImposterProtoChunk))
                        ? Blocks.VOID_AIR.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
            }
        }

        LightChunk chunk = this.getChunk(cx, cz);
        if (chunk == null) {
            return Blocks.BEDROCK.defaultBlockState();
        }
        if (chunk instanceof EmptyLevelChunk) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        if (chunk instanceof ChunkAccess ca && !(ca instanceof LevelChunk lc && lc.getLevel().isDebug())) {
            int secIdx = ca.getSectionIndex(y);
            LevelChunkSection[] secs = ca.getSections();
            if (secIdx >= 0 && secIdx < secs.length) {
                LevelChunkSection sec = secs[secIdx];
                this.chunking$lastSecChunkX = cx;
                this.chunking$lastSecChunkZ = cz;
                this.chunking$lastSecIdx = secIdx;
                this.chunking$lastSection = sec;
                this.chunking$lastChunkAccess = ca;
                return (sec == null || sec.hasOnlyAir()) ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
            }
            return (ca instanceof ProtoChunk && !(ca instanceof ImposterProtoChunk))
                    ? Blocks.VOID_AIR.defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
        }
        if (posFallback instanceof BlockPos.MutableBlockPos mut) {
            mut.set(x, y, z);
        }
        return chunk.getBlockState(posFallback);
    }

    /**
     * @author Chunking
     * @reason Branchless direct field read of BlockStateBase.lightDampening across all dimensions and modded blocks.
     */
    @Overwrite
    protected int getOpacity(BlockState state) {
        int d = state.getLightDampening();
        return d > 1 ? d : 1;
    }

    /**
     * @author Chunking
     * @reason Bypass LayerLightSectionStorage.getStoredLevel (and any mod @Inject boxing) via cached updating DataLayer lookup.
     */
    @Overwrite
    private int propagateIncreases() {
        LightUpdatingAccess upd = (LightUpdatingAccess) this.storage;
        int count = 0;
        while (!this.increaseQueue.isEmpty()) {
            long blockNode = this.increaseQueue.dequeueLong();
            long queueEntry = this.increaseQueue.dequeueLong();
            int storedLevel = upd.chunking$getStoredLevelFast(blockNode);
            int fromLevel = LightEngine.QueueEntry.getFromLevel(queueEntry);
            if (LightEngine.QueueEntry.isIncreaseFromEmission(queueEntry) && storedLevel < fromLevel) {
                upd.chunking$setStoredLevelFast(blockNode, fromLevel);
                storedLevel = fromLevel;
            }
            if (storedLevel == fromLevel) {
                this.propagateIncrease(blockNode, queueEntry, storedLevel);
            }
            count++;
        }
        return count;
    }

    /**
     * @author Chunking
     * @reason Short-circuit shapeOccludes when neither block uses shape-based light occlusion.
     */
    @Overwrite
    protected boolean shapeOccludes(BlockState fromState, BlockState toState, Direction dir) {
        if (!fromState.useShapeForLightOcclusion() && !toState.useShapeForLightOcclusion()) {
            return false;
        }
        return Shapes.faceShapeOccludes(
                getOcclusionShape(fromState, dir),
                getOcclusionShape(toState, dir.getOpposite())
        );
    }

    @Override
    public com.chunking.engine.access.LightUpdatingAccess chunking$getStorage() {
        return (com.chunking.engine.access.LightUpdatingAccess) this.storage;
    }

    @Override
    public LightChunk chunking$getChunk(int cx, int cz) {
        return this.getChunk(cx, cz);
    }

    @Override
    public BlockState chunking$getState(BlockPos pos) {
        return this.getState(pos);
    }

    @Override
    public BlockState chunking$getState(int x, int y, int z, BlockPos.MutableBlockPos fallbackPos) {
        return chunking$getStateCoords(x, y, z, fallbackPos);
    }

    @Override
    public int chunking$getOpacity(BlockState state) {
        return this.getOpacity(state);
    }

    @Override
    public boolean chunking$shapeOccludes(BlockState fromState, BlockState toState, Direction dir) {
        return this.shapeOccludes(fromState, toState, dir);
    }

    @Override
    public void chunking$enqueueIncrease(long pos, long entry) {
        this.enqueueIncrease(pos, entry);
    }

    @Override
    public void chunking$enqueueDecrease(long pos, long entry) {
        this.enqueueDecrease(pos, entry);
    }
}
