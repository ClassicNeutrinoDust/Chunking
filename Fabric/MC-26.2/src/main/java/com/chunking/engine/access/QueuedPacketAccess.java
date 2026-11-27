package com.chunking.engine.access;

import net.minecraft.network.protocol.Packet;

/**
 * Duck access to queued entries in Minecraft's {@code PacketProcessor$ListenerAndPacket} so
 * client frame-budgeted draining stays strictly FIFO without touching non-chunk gameplay packets.
 */
public interface QueuedPacketAccess {
    Packet<?> chunking$packet();

    void chunking$handle();
}
