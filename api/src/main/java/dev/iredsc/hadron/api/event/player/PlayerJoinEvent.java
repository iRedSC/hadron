package dev.iredsc.hadron.api.event.player;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player has joined and is in the world. {@link #getJoinMessage()} is the "joined the game" line about to be
 * broadcast; change it with {@link #setJoinMessage}, or set null for none. It replaces vanilla's line, and is sent
 * once listeners finish.
 */
public class PlayerJoinEvent extends PlayerEvent {
    private Component joinMessage;

    public PlayerJoinEvent(ServerPlayer player) {
        this(player, null);
    }

    /** @param joinMessage the line about to be broadcast, or null for none */
    public PlayerJoinEvent(ServerPlayer player, Component joinMessage) {
        super(player);
        this.joinMessage = joinMessage;
    }

    /** The line about to be broadcast to everyone, or null when none will be. */
    public Component getJoinMessage() {
        return joinMessage;
    }

    /** Broadcast this instead; null or an empty component broadcasts nothing. */
    public void setJoinMessage(Component joinMessage) {
        this.joinMessage = joinMessage;
    }
}
