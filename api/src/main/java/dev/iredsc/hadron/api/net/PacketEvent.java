package dev.iredsc.hadron.api.net;

import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;

/**
 * One packet on its way to or from one player. Not on the server thread (see {@link Packets}): read the packet and the
 * player's identity here, and hop to the server thread with {@link #runOnServerThread} for anything else.
 */
public interface PacketEvent<T extends Packet<?>> extends Cancellable {
    /** Who the packet is sent to, or who sent it. */
    ServerPlayer getPlayer();

    /** The packet as it stands now: an earlier handler may have replaced it. */
    T getPacket();

    /**
     * Sends or handles this packet instead, for this player only. Packets are immutable, so build a new one. To send
     * a different kind of packet, cancel this one and send yours with {@code player.connection.send}.
     *
     * @throws IllegalStateException in a MONITOR handler, which may only look
     */
    void setPacket(T packet);

    /** True if the packet won't be sent or handled. A later handler may uncancel it. */
    @Override
    boolean isCancelled();

    /**
     * Drops the packet: it is not sent to the player, or the server never handles it.
     *
     * @throws IllegalStateException in a MONITOR handler, which may only look
     */
    @Override
    void setCancelled(boolean cancel);

    /** True for a packet the server sends, false for one the player sent. */
    boolean isOutgoing();

    /**
     * Runs the task on the server thread at the end of the current or next tick, as the handler's plugin, so it may
     * touch the world. Skipped if the plugin disables first. The packet itself has been sent or handled by then.
     */
    void runOnServerThread(Runnable task);
}
