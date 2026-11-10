package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * Anything that happens to an inventory, identified by what holds it ({@link #getHolder()}), not by where a player
 * stands. Listen for this to hear opens, clicks and changes from every actor.
 */
public abstract class InventoryEvent extends ActionEvent {
    private final InventoryHolder holder;

    protected InventoryEvent(InventoryHolder holder, Actor actor) {
        super(actor);
        this.holder = Objects.requireNonNull(holder,
                "holder; use InventoryHolder.Unknown.INSTANCE when nothing is known");
    }

    /** What holds the inventory this is about. */
    public InventoryHolder getHolder() {
        return holder;
    }

    /**
     * The player who did it, if a player is behind it and online: directly, or as the owner of the machine or fake
     * player that did. Empty for hoppers with no owner. Same as {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }

    /** Where the inventory is ({@link Place#of(InventoryHolder)} of {@link #getHolder()}). */
    @Override
    public Places getPlaces() {
        return Place.of(getHolder());
    }
}
