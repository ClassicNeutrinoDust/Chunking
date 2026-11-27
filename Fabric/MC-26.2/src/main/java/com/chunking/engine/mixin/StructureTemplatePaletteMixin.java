package com.chunking.engine.mixin;

import com.chunking.engine.world.TemplateGrid;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe block/jigsaw caches and lazy coarse XZ spatial index on {@link StructureTemplate.Palette}.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructureTemplatePaletteMixin implements TemplateGrid.Access {
    @Shadow
    @Final
    private List<StructureTemplate.StructureBlockInfo> blocks;

    @Mutable
    @Shadow
    @Final
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Unique
    private volatile List<StructureTemplate.JigsawBlockInfo> chunking$volatileJigsaws;

    @Unique
    private volatile TemplateGrid chunking$spatialIndex;

    @Unique
    private volatile boolean chunking$spatialIndexReady;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunking$useConcurrentCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }

    @WrapMethod(method = "blocks(Lnet/minecraft/world/level/block/Block;)Ljava/util/List;")
    private List<StructureTemplate.StructureBlockInfo> chunking$guardNullBlockLookup(
            Block block,
            Operation<List<StructureTemplate.StructureBlockInfo>> original
    ) {
        if (block == null) {
            return List.of();
        }
        return original.call(block);
    }

    @WrapMethod(method = "jigsaws")
    private List<StructureTemplate.JigsawBlockInfo> chunking$syncJigsaws(
            Operation<List<StructureTemplate.JigsawBlockInfo>> original
    ) {
        List<StructureTemplate.JigsawBlockInfo> existing = this.chunking$volatileJigsaws;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = this.chunking$volatileJigsaws;
            if (existing != null) {
                return existing;
            }
            List<StructureTemplate.JigsawBlockInfo> res = original.call();
            this.chunking$volatileJigsaws = res;
            return res;
        }
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> chunking$queryBlocks(
            BlockPos origin,
            StructurePlaceSettings settings
    ) {
        List<StructureTemplate.StructureBlockInfo> all = this.blocks;
        if (all.size() < TemplateGrid.MIN_INDEX_BLOCKS) {
            return all;
        }
        TemplateGrid index = this.chunking$spatialIndex;
        if (!this.chunking$spatialIndexReady) {
            synchronized (this) {
                if (!this.chunking$spatialIndexReady) {
                    index = TemplateGrid.build(all);
                    this.chunking$spatialIndex = index;
                    this.chunking$spatialIndexReady = true;
                } else {
                    index = this.chunking$spatialIndex;
                }
            }
        }
        return index != null ? index.query(origin, settings) : all;
    }
}
