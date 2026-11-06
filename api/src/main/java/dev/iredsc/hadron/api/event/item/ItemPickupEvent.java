package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * An actor is about to pick an item entity up. Replaces Bukkit's {@code EntityPickupItemEvent} and
 * {@code InventoryPickupItemEvent}. Cancel it and the item stays on the ground, untouched.
 *
 * <p>Core fires it for players and fake players, hoppers, hopper minecarts, and mobs (zombies taking armour, piglins
 * taking gold, allays, foxes, villagers), only when the item is actually about to be taken. Bridges fire it for magnets
 * and vacuum hoppers, with the final destination, such as a backpack.
 */
public class ItemPickupEvent extends ItemEvent implements Cancellable {
    private final ItemEntity entity;
    private final InventoryHolder destination;
    private boolean cancelled;

    public ItemPickupEvent(ServerLevel level, ItemEntity entity, InventoryHolder destination, Actor actor) {
        super(level, Objects.requireNonNull(entity, "entity").getItem(), actor);
        this.entity = entity;
        this.destination = Objects.requireNonNull(destination,
                "destination; use InventoryHolder.Unknown.INSTANCE when nothing is known");
    }

    /** The item entity on the ground. {@link #getItem()} is its live stack. */
    public ItemEntity getItemEntity() {
        return entity;
    }

    /** Where the item goes: the player, the hopper, the mob, or a backpack inside one. */
    public InventoryHolder getDestination() {
        return destination;
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
