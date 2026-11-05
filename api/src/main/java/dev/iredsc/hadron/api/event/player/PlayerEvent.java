package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Event;
import net.minecraft.server.level.ServerPlayer;

/** Anything about one player. Listen for this to hear all of them. */
public abstract class PlayerEvent extends Event {
    private final ServerPlayer player;

    protected PlayerEvent(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
