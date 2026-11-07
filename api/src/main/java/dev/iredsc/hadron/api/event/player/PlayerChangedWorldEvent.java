package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A player has moved to another world (dimension); {@code getPlayer().serverLevel()} is the new one. After the fact. */
@FiredBy(core = FULL)
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
