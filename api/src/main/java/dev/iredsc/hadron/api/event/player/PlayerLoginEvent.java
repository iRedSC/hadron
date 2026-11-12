package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.FiredBy;
import org.jspecify.annotations.Nullable;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player has passed the server's own checks (whitelist, bans, a full server) and is about to join. They are not in
 * the world yet: read who they are, then {@link #disallow} to turn them away with a message.
 * {@link PlayerJoinEvent} follows if they're let in.
 */
@FiredBy(core = FULL)
public class PlayerLoginEvent extends PlayerEvent {
    private final SocketAddress address;
    private @Nullable Component kickMessage;

    public PlayerLoginEvent(ServerPlayer player, SocketAddress address) {
        super(player);
        this.address = address;
    }

    /** Where they're connecting from. */
    public SocketAddress getAddress() {
        return address;
    }

    /** Turns them away; they see the message on their disconnect screen. */
    public void disallow(Component message) {
        this.kickMessage = Objects.requireNonNull(message, "message; the player needs to see why they can't join");
    }

    /** Lets them in after all, undoing an earlier {@link #disallow}. */
    public void allow() {
        this.kickMessage = null;
    }

    public boolean isAllowed() {
        return kickMessage == null;
    }

    /** Why they're turned away; empty while they're allowed. */
    public Optional<Component> getKickMessage() {
        return Optional.ofNullable(kickMessage);
    }

    /** Nowhere yet: the player isn't in a world until they join. Their own account ({@link Place.Nowhere#player}). */
    @Override
    public Places getPlaces() {
        return Places.of(Place.Nowhere.player(getPlayer().getUUID()));
    }
}
