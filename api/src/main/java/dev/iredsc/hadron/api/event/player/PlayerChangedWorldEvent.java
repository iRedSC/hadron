package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** A player has moved to another world (dimension); {@code getPlayer().serverLevel()} is the new one. After the fact. */
public class PlayerChangedWorldEvent extends PlayerEvent {
    private final ServerLevel from;

    public PlayerChangedWorldEvent(ServerPlayer player, ServerLevel from) {
        super(player);
        this.from = from;
    }

    /** The world they left. */
    public ServerLevel getFrom() {
        return from;
    }
}
