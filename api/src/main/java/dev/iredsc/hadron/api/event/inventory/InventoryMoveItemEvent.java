package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

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
@FiredBy(core = PARTIAL,
        coreSees = "hoppers, hopper minecarts and droppers, into and out of vanilla containers and mods' item handlers",
        bridges = "the mod's pipes and networks")
public class InventoryMoveItemEvent extends InventoryChangeEvent {
    private final InventoryHolder source;
    private final InventoryHolder destination;
    private final InventoryHolder initiator;
    private final EventStack.Copy item;

    /**
     * Items leaving a known slot.
     *
     * @param sourceSlot the slot the items leave, 0 or more
     * @param sourceBefore that slot's stack before the move: the same item and components as {@code item}, and at least
     *     as many
     * @param item exactly what moves; not empty
     * @param initiator whichever of the two is doing the moving: the hopper, pipe or bus
     */
    public InventoryMoveItemEvent(InventoryHolder source, int sourceSlot, ItemStack sourceBefore,
            InventoryHolder destination, ItemStack item, InventoryHolder initiator, Actor actor) {
        this(fromSlot(source, sourceSlot, sourceBefore, item), source, destination, item, initiator, actor);
    }

    /**
     * Items leaving from wherever the source takes them ({@link Change#ANY_SLOT}), such as an item handler that
     * doesn't say which slot.
     *
     * @param item exactly what moves; not empty
     * @param initiator whichever of the two is doing the moving: the hopper, pipe or bus
     */
    public InventoryMoveItemEvent(InventoryHolder source, InventoryHolder destination, ItemStack item,
            InventoryHolder initiator, Actor actor) {
        this(Change.leaving(source, moving(item)), source, destination, item, initiator, actor);
    }

    private InventoryMoveItemEvent(Change leaving, InventoryHolder source, InventoryHolder destination, ItemStack item,
            InventoryHolder initiator, Actor actor) {
        super(List.of(leaving, Change.arriving(Objects.requireNonNull(destination, "destination"), item)), actor);
        this.source = source;
        this.destination = destination;
        this.initiator = Objects.requireNonNull(initiator, "initiator");
        this.item = EventStack.copyOf(item, "item");
    }

    private static ItemStack moving(ItemStack item) {
        Objects.requireNonNull(item, "item is null; pass exactly what moves");
        if (item.isEmpty()) {
            throw new IllegalArgumentException("item is empty, so nothing moves: fire no InventoryMoveItemEvent when "
                    + "the move would carry nothing");
        }
        return item;
    }

    /** The source slot's change: what it held, and what it holds once {@code item} has left it. */
    private static Change fromSlot(InventoryHolder source, int slot, ItemStack before, ItemStack item) {
        moving(item);
        Objects.requireNonNull(before, "sourceBefore is null; pass the source slot's stack before the move");
        if (slot < 0) {
            throw new IllegalArgumentException("sourceSlot " + slot + " isn't a slot: pass the slot's index, or use "
                    + "new InventoryMoveItemEvent(source, destination, item, initiator, actor) when the source picks "
                    + "the slot itself");
        }
        if (!ItemStack.isSameItemSameComponents(before, item) || before.getCount() < item.getCount()) {
            throw new IllegalArgumentException("The moving items (" + item + ") aren't in the source slot's stack ("
                    + before + "): pass as sourceBefore the slot's whole stack before the move, and as item only "
                    + "what leaves it, such as sourceBefore.copyWithCount(n) with n at most its count");
        }
        ItemStack after = before.copy();
        after.shrink(item.getCount());
        return new Change(source, slot, before, after);
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
        return item.get();
    }
}
