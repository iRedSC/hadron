package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** A player sent a chat message. Cancel it to send nothing, or replace what everyone sees with {@link #setMessage}. */
public class PlayerChatEvent extends PlayerEvent implements Cancellable {
    private final String rawText;
    private Component message;
    private boolean cancelled;

    public PlayerChatEvent(ServerPlayer player, String rawText, Component message) {
        super(player);
        this.rawText = rawText;
        this.message = message;
    }

    /** Exactly what the player typed. */
    public String getRawText() {
        return rawText;
    }

    /** What everyone will see. */
    public Component getMessage() {
        return message;
    }

    public void setMessage(Component message) {
        this.message = message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
