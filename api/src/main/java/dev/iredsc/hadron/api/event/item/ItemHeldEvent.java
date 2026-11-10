package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is about to change their selected hotbar slot. Replaces Bukkit's {@code PlayerItemHeldEvent}. Cancel it
 * and the selection stays; the player's client is moved back. Always a player, so {@link #getPlayer()} is never empty;
 * {@link #getUser()} returns them directly. {@link #getItem()} is the stack in the new slot (live; don't change it).
 */
@FiredBy(core = FULL)
public class ItemHeldEvent extends ItemEvent implements Cancellable {
    private final ServerPlayer user;
    private final int previousSlot;
    private final int newSlot;
    private boolean cancelled;

    public ItemHeldEvent(ServerPlayer player, int previousSlot, int newSlot, Actor actor) {
        super(Objects.requireNonNull(player, "player").serverLevel(),
                EventStack.live(player.getInventory().getItem(newSlot), "the new slot's item"), actor);
        this.user = player;
        this.previousSlot = previousSlot;
        this.newSlot = newSlot;
    }

    /** The player changing slot. */
    public ServerPlayer getUser() {
        return user;
    }

    /** The hotbar slot (0-8) selected until now. */
    public int getPreviousSlot() {
        return previousSlot;
    }

    /** The hotbar slot (0-8) being selected. */
    public int getNewSlot() {
        return newSlot;
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
