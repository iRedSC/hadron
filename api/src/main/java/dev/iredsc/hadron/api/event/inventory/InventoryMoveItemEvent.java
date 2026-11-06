package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Items are about to move from one inventory to another: a hopper pushing or pulling, a dropper inserting, a pipe, an
 * export bus. Cancel it and the items stay where they were, whole.
 *
 * <p>Core fires it for hoppers, hopper minecarts and droppers, into and out of vanilla containers and any mod
 * container with an item handler, and only when the destination has room, so a hopper checking a full chest fires
 * nothing. A hopper whose move was cancelled tries again on its next cycle, firing again. Bridges fire it for their
 * mod's pipes and networks, for committed transfers only, never simulated ones.
 *
 * <p>{@link #getHolder()} is the source. {@link #getChanges()} has the source's slot (before and after) and the items
 * arriving at the destination ({@link Change#ANY_SLOT} when the destination picks the slot).
 */
public class InventoryMoveItemEvent extends InventoryChangeEvent {
    private final InventoryHolder source;
    private final InventoryHolder destination;
    private final InventoryHolder initiator;
    private final ItemStack item;

    /**
     * @param sourceSlot the slot the items leave, or {@link Change#ANY_SLOT}
     * @param sourceBefore that slot's stack before the move (the moving items themselves if the slot is unknown)
     * @param item exactly what moves
     * @param initiator whichever of the two is doing the moving: the hopper, pipe or bus
     */
    public InventoryMoveItemEvent(InventoryHolder source, int sourceSlot, ItemStack sourceBefore,
            InventoryHolder destination, ItemStack item, InventoryHolder initiator, Actor actor) {
        super(changes(source, sourceSlot, sourceBefore, destination, item), actor);
        this.source = source;
        this.destination = Objects.requireNonNull(destination, "destination");
        this.initiator = Objects.requireNonNull(initiator, "initiator");
        this.item = item.copy();
    }

    private static List<Change> changes(InventoryHolder source, int slot, ItemStack before, InventoryHolder destination,
            ItemStack item) {
        Objects.requireNonNull(item, "item");
        if (slot == Change.ANY_SLOT) {
            return List.of(Change.leaving(source, item), Change.arriving(destination, item));
        }
        ItemStack after = before.copy();
        after.shrink(item.getCount());
        return List.of(new Change(source, slot, before, after), Change.arriving(destination, item));
    }

    /** Where the items come from. */
    public InventoryHolder getSource() {
        return source;
    }

    /** Where the items go. */
    public InventoryHolder getDestination() {
        return destination;
    }

    /** Which side is doing the moving: the hopper, whether it is pushing or pulling. */
    public InventoryHolder getInitiator() {
        return initiator;
    }

    /** What moves. A copy; editing it changes nothing. */
    public ItemStack getItem() {
        return item.copy();
    }
}
