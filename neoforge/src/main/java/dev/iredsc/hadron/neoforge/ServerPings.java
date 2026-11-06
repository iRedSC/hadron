package dev.iredsc.hadron.neoforge;

import com.mojang.authlib.GameProfile;
import dev.iredsc.hadron.api.event.server.ServerListPingEvent;
import java.net.SocketAddress;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.protocol.status.ServerStatus;

/** {@link ServerListPingEvent}, from the status ping handler on a network thread. */
public final class ServerPings {
    private ServerPings() {}

    /** The answer to send: {@code status} itself when no listener changed anything, so its cached JSON stays valid. */
    public static ServerStatus ping(SocketAddress address, ServerStatus status) {
        if (!Hooks.listens(ServerListPingEvent.class)) return status;
        Optional<ServerStatus.Players> players = status.players();
        int online = players.map(ServerStatus.Players::online).orElse(0);
        int max = players.map(ServerStatus.Players::max).orElse(0);
        List<GameProfile> sample = players.map(ServerStatus.Players::sample).orElse(List.of());
        ServerListPingEvent event = Hooks.post(new ServerListPingEvent(address, status.description(), online, max,
                sample));
        boolean playersChanged = event.getNumPlayers() != online || event.getMaxPlayers() != max
                || !event.getPlayerSample().equals(sample);
        if (event.getMotd().equals(status.description()) && !playersChanged) return status;
        Optional<ServerStatus.Players> shown = playersChanged
                ? Optional.of(new ServerStatus.Players(event.getMaxPlayers(), event.getNumPlayers(),
                        List.copyOf(event.getPlayerSample())))
                : players;
        return new ServerStatus(event.getMotd(), shown, status.version(), status.favicon(),
                status.enforcesSecureChat(), status.isModded());
    }
}
