package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A player sent a chat message. Cancel it to send nothing, or replace what everyone sees with {@link #setMessage}. */
@FiredBy(core = FULL)
public class PlayerChatEvent extends PlayerEvent implements Cancellable {
    private final String rawText;
    private Component message;
    private boolean cancelled;

    public PlayerChatEvent(ServerPlayer player, String rawText, Component message) {
        super(player);
        this.rawText = rawText;
        this.message = Objects.requireNonNull(message, "Chat message cannot be null; use setCancelled(true) to hide it");
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
        this.message = Objects.requireNonNull(message, "Chat message cannot be null; use setCancelled(true) to hide it");
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
