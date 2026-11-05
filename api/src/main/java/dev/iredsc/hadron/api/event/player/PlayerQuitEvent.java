package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerPlayer;

/** A player is leaving; they're still in the world while this runs, so it's the place to save their data. */
public class PlayerQuitEvent extends PlayerEvent {
    public PlayerQuitEvent(ServerPlayer player) {
        super(player);
    }
}
