package com.chunking.engine.mixin.client;

import com.chunking.engine.access.QueuedPacketAccess;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes packet inspection and execution on Minecraft's package-private
 * {@code PacketProcessor$ListenerAndPacket} record for frame-budgeted client packet draining.
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class ListenerAndPacketMixin implements QueuedPacketAccess {
    @Shadow
    @Final
    private Packet<?> packet;

    @Shadow
    public abstract void handle();

    @Override
    public Packet<?> chunking$packet() {
        return this.packet;
    }

    @Override
    public void chunking$handle() {
        this.handle();
    }
}
