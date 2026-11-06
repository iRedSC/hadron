package dev.iredsc.hadron.api.event.server;

import com.mojang.authlib.GameProfile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * Someone's server list is asking about this server, and the answer is about to be sent: the MOTD, the player counts
 * and the few names shown when hovering over them. Change any of them to change what that client sees. Fired for
 * every modern status ping (Minecraft 1.7 and later); clients older than that get vanilla's answer.
 *
 * <p>Runs on a network thread, not the server thread: read what you need, but don't touch worlds or players.
 */
public class ServerListPingEvent extends ServerEvent {
    private final SocketAddress address;
    private Component motd;
    private int maxPlayers;
    private int numPlayers;
    private final List<GameProfile> sample;

    public ServerListPingEvent(SocketAddress address, Component motd, int numPlayers, int maxPlayers,
            List<GameProfile> sample) {
        this.address = address;
        this.motd = Objects.requireNonNull(motd, "motd; use Component.empty()");
        this.numPlayers = numPlayers;
        this.maxPlayers = maxPlayers;
        this.sample = new ArrayList<>(sample);
    }

    /** Where the ping came from; empty for a connection without an IP address (an in-memory one). */
    public Optional<InetAddress> getAddress() {
        return address instanceof InetSocketAddress inet ? Optional.ofNullable(inet.getAddress()) : Optional.empty();
    }

    /** The message of the day shown under the server's name. */
    public Component getMotd() {
        return motd;
    }

    /** Show this message of the day instead; {@code Component.empty()} shows none. */
    public void setMotd(Component motd) {
        this.motd = Objects.requireNonNull(motd, "motd; use Component.empty() to show none");
    }

    /** The maximum player count shown. */
    public int getMaxPlayers() {
        return maxPlayers;
    }

    /** Show this as the maximum; only what the list shows, not how many can join. */
    public void setMaxPlayers(int maxPlayers) {
        if (maxPlayers < 0) throw new IllegalArgumentException("maxPlayers can't be negative (got " + maxPlayers + ")");
        this.maxPlayers = maxPlayers;
    }

    /** The online player count shown. */
    public int getNumPlayers() {
        return numPlayers;
    }

    /** Show this as the online count; only what the list shows. */
    public void setNumPlayers(int numPlayers) {
        if (numPlayers < 0) throw new IllegalArgumentException("numPlayers can't be negative (got " + numPlayers + ")");
        this.numPlayers = numPlayers;
    }

    /**
     * The players listed when hovering over the count. Editable: remove names to hide them, {@code clear()} to show
     * none, or add profiles (any name, with a random UUID) to show lines of text.
     */
    public List<GameProfile> getPlayerSample() {
        return sample;
    }
}
