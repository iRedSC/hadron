package dev.iredsc.hadron.api.net;

import net.minecraft.network.protocol.Packet;

/** What a plugin runs for each packet it listens to. Not on the server thread: see {@link Packets}. */
@FunctionalInterface
public interface PacketHandler<T extends Packet<?>> {
    void handle(PacketEvent<T> event);
}
