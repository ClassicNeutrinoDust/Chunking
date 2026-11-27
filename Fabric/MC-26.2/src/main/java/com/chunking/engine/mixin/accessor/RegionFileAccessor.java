package com.chunking.engine.mixin.accessor;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.ByteBuffer;

@Mixin(RegionFile.class)
public interface RegionFileAccessor {
    @Invoker("write")
    void chunking$invokeWrite(ChunkPos pos, ByteBuffer data) throws IOException;

    @Accessor("version")
    RegionFileVersion chunking$getVersion();
}
