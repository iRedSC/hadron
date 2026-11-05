package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerPlayer;

/** A player has joined and is in the world. */
public class PlayerJoinEvent extends PlayerEvent {
    public PlayerJoinEvent(ServerPlayer player) {
        super(player);
    }
}
