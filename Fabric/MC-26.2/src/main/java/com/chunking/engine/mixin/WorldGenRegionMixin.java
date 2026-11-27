package com.chunking.engine.mixin;

import com.chunking.engine.config.ModConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.TickContainerAccess;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.Objects;

/**
 * Eliminates the repeated {@code StaticCache2D -> GenerationChunkHolder -> CompletableFuture.getNow}
 * lookup and {@code @WrapMethod} lambda allocations on every {@code getChunk}
 * call during chunk generation by flat-caching resolved {@link ChunkAccess} references for the
 * {@link WorldGenRegion}'s immutable dependency grid, and fast-paths {@link WorldGenRegion#getBlockState},
 * {@link WorldGenRegion#getHeight}, and {@link WorldGenRegion#getChunk(int, int)} directly through
 * the O(1) flat cache.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin implements LevelReader {
    @Shadow
    @Final
    private StaticCache2D<GenerationChunkHolder> cache;

    @Shadow
    @Final
    private ChunkAccess center;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private ChunkStep generatingStep;

    @Shadow
    @Final
    private int centerChunkX;

    @Shadow
    @Final
    private int centerChunkZ;

    @Shadow
    @Final
    private int writeRadius;

    @Shadow
    public abstract boolean ensureCanWrite(BlockPos pos);

    @Shadow
    private void markPosForPostProcessing(BlockPos pos) {
        throw new AssertionError();
    }

    @Unique
    private ChunkAccess[] chunking$chunkCache;

    @Unique
    private int chunking$radius;

    @Unique
    private int chunking$side;

    @Unique
    private ChunkStatus[] chunking$allowedByDist;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$initFlatChunkCache(CallbackInfo ci) {
        ChunkDependencies deps = this.generatingStep.directDependencies();
        int count = deps.size();
        ChunkStatus[] allowed = new ChunkStatus[count];
        for (int i = 0; i < count; i++) {
            allowed[i] = deps.get(i);
        }
        int r = Math.max(0, count - 1);
        int side = r * 2 + 1;
        ChunkAccess[] flat = new ChunkAccess[side * side];
        flat[r + r * side] = this.center;
        this.chunking$allowedByDist = allowed;
        this.chunking$radius = r;
        this.chunking$side = side;
        this.chunking$chunkCache = flat;
    }

    @Inject(method = "warnIfReadOutsideWriteZone", at = @At("HEAD"), cancellable = true)
    private void chunking$skipReadOutsideWriteZoneWarning(int chunkX, int chunkZ, CallbackInfo ci) {
        if (ModConfig.PARALLEL_STATUS_TASKS) {
            ci.cancel();
        }
    }

    @Unique
    private ChunkAccess chunking$fastGetChunkEmpty(int cx, int cz) {
        if (cx == this.centerChunkX && cz == this.centerChunkZ) {
            return this.center;
        }
        int dx = cx - this.centerChunkX;
        int dz = cz - this.centerChunkZ;
        int r = this.chunking$radius;
        ChunkAccess[] flat = this.chunking$chunkCache;
        if (flat != null && dx >= -r && dx <= r && dz >= -r && dz <= r) {
            ChunkAccess chunk = flat[(dx + r) + (dz + r) * this.chunking$side];
            if (chunk != null) {
                return chunk;
            }
        }
        return this.getChunk(cx, cz, ChunkStatus.EMPTY, true);
    }

    /**
     * @author Chunking
     * @reason Direct O(1) center/flat-grid lookup for default ChunkStatus.EMPTY chunk requests.
     */
    @Overwrite
    public ChunkAccess getChunk(int chunkX, int chunkZ) {
        return this.chunking$fastGetChunkEmpty(chunkX, chunkZ);
    }

    /**
     * @author Chunking
     * @reason Direct O(1) center/flat-grid heightmap lookup without getChunk status-check overhead.
     */
    @Overwrite
    public int getHeight(Heightmap.Types heightmap, int x, int z) {
        return this.chunking$fastGetChunkEmpty(x >> 4, z >> 4).getHeight(heightmap, x & 15, z & 15) + 1;
    }

    @Unique
    private static LevelChunkSection chunking$resolveSection(ChunkAccess chunk, int by) {
        Class<?> cls = chunk.getClass();
        if (cls == ProtoChunk.class) {
            return chunk.isOutsideBuildHeight(by) ? null : chunk.getSection(chunk.getSectionIndex(by));
        }
        if (cls == ImposterProtoChunk.class) {
            LevelChunk wrapped = ((ImposterProtoChunk) chunk).getWrapped();
            return wrapped.isOutsideBuildHeight(by) ? null : wrapped.getSection(wrapped.getSectionIndex(by));
        }
        if (cls == LevelChunk.class && !((LevelChunk) chunk).getLevel().isDebug()) {
            return chunk.isOutsideBuildHeight(by) ? null : chunk.getSection(chunk.getSectionIndex(by));
        }
        return null;
    }

    /**
     * @author Chunking
     * @reason Direct flat-cache section lookup without getChunk status checks.
     */
    @Overwrite
    public BlockState getBlockState(BlockPos pos) {
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        ChunkAccess chunk = this.chunking$fastGetChunkEmpty(bx >> 4, bz >> 4);
        LevelChunkSection section = chunking$resolveSection(chunk, by);
        if (section != null) {
            return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(bx & 15, by & 15, bz & 15);
        }
        if (chunk.getClass() == ProtoChunk.class && chunk.isOutsideBuildHeight(by)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        if ((chunk.getClass() == ImposterProtoChunk.class || chunk.getClass() == LevelChunk.class) && chunk.isOutsideBuildHeight(by)) {
            return Blocks.AIR.defaultBlockState();
        }
        return chunk.getBlockState(pos);
    }

    /**
     * @author Chunking
     * @reason Direct flat-cache fluid lookup without warnIfReadOutsideWriteZone.
     */
    @Overwrite
    public FluidState getFluidState(BlockPos pos) {
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        ChunkAccess chunk = this.chunking$fastGetChunkEmpty(bx >> 4, bz >> 4);
        LevelChunkSection section = chunking$resolveSection(chunk, by);
        if (section != null) {
            return section.hasOnlyAir() ? Fluids.EMPTY.defaultFluidState() : section.getFluidState(bx & 15, by & 15, bz & 15);
        }
        if (chunk.isOutsideBuildHeight(by) && (chunk.getClass() == ProtoChunk.class
                || chunk.getClass() == ImposterProtoChunk.class || chunk.getClass() == LevelChunk.class)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return chunk.getFluidState(pos);
    }

    @Override
    public boolean isEmptyBlock(BlockPos pos) {
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        ChunkAccess chunk = this.chunking$fastGetChunkEmpty(bx >> 4, bz >> 4);
        LevelChunkSection section = chunking$resolveSection(chunk, by);
        if (section != null) {
            return section.hasOnlyAir() || section.getBlockState(bx & 15, by & 15, bz & 15).isAir();
        }
        if (chunk.isOutsideBuildHeight(by) && (chunk.getClass() == ProtoChunk.class
                || chunk.getClass() == ImposterProtoChunk.class || chunk.getClass() == LevelChunk.class)) {
            return true;
        }
        return chunk.getBlockState(pos).isAir();
    }

    /**
     * @author Chunking
     * @reason Integer write-zone fast check, O(1) flat chunk lookup, monomorphic ProtoChunk write, and no-op/non-BE fast exits.
     */
    @Overwrite
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        int bx = pos.getX();
        int bz = pos.getZ();
        int cx = bx >> 4;
        int cz = bz >> 4;
        int dx = cx - this.centerChunkX;
        int dz = cz - this.centerChunkZ;
        int wr = this.writeRadius;
        if (dx < -wr || dx > wr || dz < -wr || dz > wr || this.center.isUpgrading()) {
            if (!this.ensureCanWrite(pos)) {
                return false;
            }
        }
        ChunkAccess chunk = this.chunking$fastGetChunkEmpty(cx, cz);
        BlockState prev = (chunk.getClass() == ProtoChunk.class)
                ? ((ProtoChunk) chunk).setBlockState(pos, state, flags)
                : chunk.setBlockState(pos, state, flags);
        boolean hasBe = state.hasBlockEntity();
        if (prev == state && !hasBe) {
            // Identical state never changes POI types and has no block entity to create or remove.
            if ((flags & 16) == 0) {
                BlockPos postPos = state.getPostProcessPos((WorldGenRegion) (Object) this, pos);
                if (postPos != null) {
                    this.markPosForPostProcessing(postPos);
                }
            }
            return true;
        }
        if (prev != null && prev != state) {
            this.level.updatePOIOnBlockStateChange(pos, prev, state);
        }
        if (hasBe) {
            if (chunk.getPersistedStatus().getChunkType() == ChunkType.LEVELCHUNK) {
                BlockEntity be = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                if (be != null) {
                    chunk.setBlockEntity(be);
                } else {
                    chunk.removeBlockEntity(pos);
                }
            } else {
                CompoundTag tag = new CompoundTag();
                tag.putInt("x", bx);
                tag.putInt("y", pos.getY());
                tag.putInt("z", bz);
                tag.putString("id", "DUMMY");
                chunk.setBlockEntityNbt(tag);
            }
        } else if (prev != null && prev.hasBlockEntity()) {
            chunk.removeBlockEntity(pos);
        }
        if ((flags & 16) == 0) {
            BlockPos postPos = state.getPostProcessPos((WorldGenRegion) (Object) this, pos);
            if (postPos != null) {
                this.markPosForPostProcessing(postPos);
            }
        }
        return true;
    }

    /**
     * @author Chunking
     * @reason Flat dependency grid chunk cache without @WrapMethod lambda capture allocations.
     */
    @Overwrite
    public ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus status, boolean nonnull) {
        ChunkStatus[] allowedByDist = this.chunking$allowedByDist;
        if (allowedByDist != null && ModConfig.PARALLEL_STATUS_TASKS) {
            int dx = chunkX - this.centerChunkX;
            int dz = chunkZ - this.centerChunkZ;
            if (dx == 0 && dz == 0) {
                if (allowedByDist.length > 0 && (status == ChunkStatus.EMPTY || status.isOrBefore(allowedByDist[0]))) {
                    return this.center;
                }
            } else {
                int r = this.chunking$radius;
                if (dx >= -r && dx <= r && dz >= -r && dz <= r) {
                    int dist = Math.max(dx < 0 ? -dx : dx, dz < 0 ? -dz : dz);
                    if (dist < allowedByDist.length) {
                        ChunkStatus allowed = allowedByDist[dist];
                        if (status == ChunkStatus.EMPTY || status.isOrBefore(allowed)) {
                            int idx = (dx + r) + (dz + r) * this.chunking$side;
                            ChunkAccess[] flat = this.chunking$chunkCache;
                            ChunkAccess cached = flat[idx];
                            if (cached != null) {
                                return cached;
                            }
                            ChunkAccess resolved = this.cache.get(chunkX, chunkZ).getChunkIfPresentUnchecked(allowed);
                            if (resolved != null) {
                                flat[idx] = resolved;
                                return resolved;
                            }
                        }
                    }
                }
            }
        }

        int distance = this.center.getPos().getChessboardDistance(chunkX, chunkZ);
        ChunkDependencies deps = this.generatingStep.directDependencies();
        ChunkStatus allowed = distance >= deps.size() ? null : deps.get(distance);
        GenerationChunkHolder holder;
        if (allowed != null) {
            holder = this.cache.get(chunkX, chunkZ);
            if (status.isOrBefore(allowed)) {
                ChunkAccess chunk = holder.getChunkIfPresentUnchecked(allowed);
                if (chunk != null) {
                    return chunk;
                }
            }
        } else {
            holder = null;
        }

        if (!nonnull) {
            return null;
        }

        CrashReport report = CrashReport.forThrowable(
                new IllegalStateException("Requested chunk unavailable during world generation"),
                "Exception generating new chunk"
        );
        CrashReportCategory category = report.addCategory("Chunk request details");
        category.setDetail("Requested chunk", String.format(Locale.ROOT, "%d, %d", chunkX, chunkZ));
        category.setDetail("Generating status", () -> this.generatingStep.targetStatus().getName());
        category.setDetail("Requested status", status::getName);
        category.setDetail("Actual status", () -> {
            if (holder == null) {
                return "[out of cache bounds]";
            }
            ChunkStatus persisted = holder.getPersistedStatus();
            return persisted == null ? "null" : persisted.getName();
        });
        category.setDetail("Maximum allowed status", () -> allowed == null ? "null" : allowed.getName());
        category.setDetail("Dependencies", deps::toString);
        category.setDetail("Requested distance", distance);
        category.setDetail("Generating chunk", Objects.requireNonNull(this.center.getPos())::toString);
        throw new ReportedException(report);
    }

    @ModifyVariable(method = "destroyBlock", at = @At("HEAD"), argsOnly = true, require = 0)
    private boolean chunking$preventItemDropDuringWorldgen(boolean drop) {
        return false;
    }

    @Dynamic
    @WrapMethod(method = "lambda$new$0", require = 0)
    private TickContainerAccess<Block> chunking$guardBlockScheduledTickWrite(
            BlockPos pos,
            Operation<TickContainerAccess<Block>> original
    ) {
        if (!this.ensureCanWrite(pos)) {
            return BlackholeTickAccess.emptyContainer();
        }
        return original.call(pos);
    }

    @Dynamic
    @WrapMethod(method = "lambda$new$1", require = 0)
    private TickContainerAccess<Fluid> chunking$guardFluidScheduledTickWrite(
            BlockPos pos,
            Operation<TickContainerAccess<Fluid>> original
    ) {
        if (!this.ensureCanWrite(pos)) {
            return BlackholeTickAccess.emptyContainer();
        }
        return original.call(pos);
    }
}
