package com.chunking.engine.mixin;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Speeds up standard RFC-1950 zlib ({@link RegionFileVersion#VERSION_DEFLATE}) region writes by
 * using {@link Deflater#BEST_SPEED} (100% vanilla {@link java.util.zip.InflaterInputStream}-compatible)
 * and releasing the native {@code z_stream} immediately on stream close instead of queuing it for GC finalization.
 */
@Mixin(RegionFileVersion.class)
public abstract class RegionFileVersionMixin {
    @Inject(method = "wrap(Ljava/io/OutputStream;)Ljava/io/OutputStream;", at = @At("HEAD"), cancellable = true)
    private void chunking$fastDeflateOutputWrap(OutputStream out, CallbackInfoReturnable<OutputStream> cir) {
        if ((Object) this == RegionFileVersion.VERSION_DEFLATE) {
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater, 4096) {
                private boolean ended;

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        if (!this.ended) {
                            this.ended = true;
                            this.def.end();
                        }
                    }
                }
            };
            cir.setReturnValue(new BufferedOutputStream(compressed, 8192));
        }
    }
}
