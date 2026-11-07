package dev.iredsc.hadron.api.net;

import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ServerGamePacketListener;

/**
 * Packet interception, ProtocolLib's job: see, cancel or replace any vanilla packet a player in game sends or is sent,
 * using Minecraft's own packet classes. Mods' custom payloads are better heard through {@code PlayerModPacketEvent}.
 *
 * <pre>{@code
 * Packets.onSend(this, ClientboundSetEntityDataPacket.class, event -> {
 *     ClientboundSetEntityDataPacket packet = event.getPacket();
 *     // read it, event.setPacket(new ClientboundSetEntityDataPacket(...)), or event.setCancelled(true)
 * });
 * Packets.onReceive(this, ServerboundMovePlayerPacket.class, EventPriority.LOWEST, event -> {
 *     if (looksLikeAFlyHack(event.getPacket())) event.setCancelled(true);
 * });
 * }</pre>
 *
 * <p><b>Threads.</b> Handlers do not run on the server thread. Received packets are handled on the player's network
 * thread, before the server sees them. Sent packets are handled on whichever thread sends them: usually the server
 * thread, sometimes a network thread. Reading the packet and the player's UUID is safe anywhere; changing the world,
 * entities or inventories is not. Use {@link PacketEvent#runOnServerThread} for that.
 *
 * <p>Handlers run lowest priority first, as with events, and see what earlier handlers did (a replaced packet, a
 * cancel). {@link EventPriority#MONITOR} handlers only look: changing the packet there throws.
 *
 * <p>A {@code ClientboundBundlePacket} (an entity's spawn data, sent together) is delivered as its parts, so a handler
 * for {@code ClientboundAddEntityPacket} sees every spawn. Only packets of the game phase are seen: login and
 * configuration have no player yet. Disconnect packets can't be intercepted; use {@code PlayerKickEvent}.
 *
 * <p>A packet a handler sends goes through every handler too, its own included: don't send the kind of packet a
 * handler handles from that handler.
 *
 * <p>Everything registered is removed when the plugin disables, and no handler is called once its {@code onDisable}
 * has begun (a call already under way on a network thread may still finish). A packet class nobody listens to costs nothing.
 */
public final class Packets {
    private Packets() {}

    /** A handler at NORMAL priority for packets the server sends this kind of to a player. */
    public static <T extends Packet<? super ClientGamePacketListener>> PacketRegistration onSend(
            JavaPlugin plugin, Class<T> type, PacketHandler<T> handler) {
        return onSend(plugin, type, EventPriority.NORMAL, handler);
    }

    /**
     * A handler for packets the server sends this kind of to a player. {@code type} must be a clientbound packet;
     * the compiler refuses a serverbound one.
     */
    public static <T extends Packet<? super ClientGamePacketListener>> PacketRegistration onSend(
            JavaPlugin plugin, Class<T> type, EventPriority priority, PacketHandler<T> handler) {
        return NetBackend.Port.get().listen(plugin, type, priority, handler, true);
    }

    /** A handler at NORMAL priority for packets of this kind a player sends the server. */
    public static <T extends Packet<? super ServerGamePacketListener>> PacketRegistration onReceive(
            JavaPlugin plugin, Class<T> type, PacketHandler<T> handler) {
        return onReceive(plugin, type, EventPriority.NORMAL, handler);
    }

    /**
     * A handler for packets of this kind a player sends the server, run before the server handles them.
     * {@code type} must be a serverbound packet; the compiler refuses a clientbound one.
     */
    public static <T extends Packet<? super ServerGamePacketListener>> PacketRegistration onReceive(
            JavaPlugin plugin, Class<T> type, EventPriority priority, PacketHandler<T> handler) {
        return NetBackend.Port.get().listen(plugin, type, priority, handler, false);
    }
}
