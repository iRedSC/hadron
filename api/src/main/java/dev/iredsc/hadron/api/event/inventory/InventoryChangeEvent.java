package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * Items in an inventory are about to change: each {@link Change} says which holder, which slot, and the stack before
 * and after. Cancel it and nothing changes, in any of the inventories listed.
 *
 * <p><b>Core does not see every slot write.</b> Most writes to a container are moves inside one action that already
 * has its own event, or work on copies and simulations, so core fires this family only at these chokepoints:
 *
 * <ul>
 *   <li>{@link InventoryMoveItemEvent}: hoppers and hopper minecarts pushing and pulling, droppers inserting, for
 *       vanilla containers and every mod container exposing an item handler.
 * </ul>
 *
 * A player moving items in an open menu is {@link InventoryClickEvent}; picking up an item entity is
 * {@code ItemPickupEvent}. Everything else (a mod's machine filling its output slot, a pipe, a storage network) reaches
 * this event only through that mod's bridge, which fires this event or a leaf for each committed change and never for
 * a simulated one. Listen here to hear all of them; the leaves say why.
 */
@FiredBy(core = PARTIAL,
        coreSees = "hoppers, hopper minecarts and droppers, as `InventoryMoveItemEvent`",
        bridges = "the mod's own slot writes: machine outputs, pipes, storage networks")
public class InventoryChangeEvent extends InventoryEvent implements Cancellable {
    private final List<Change> changes;
    private boolean cancelled;

    /**
     * @param changes at least one; the first change's holder is {@link #getHolder()}
     */
    public InventoryChangeEvent(List<Change> changes, Actor actor) {
        super(first(changes).holder(), actor);
        this.changes = List.copyOf(changes);
    }

    private static Change first(List<Change> changes) {
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("An InventoryChangeEvent needs at least one Change: pass one per slot "
                    + "(or one with Change.ANY_SLOT when the inventory picks the slot itself)");
        }
        return changes.get(0);
    }

    /** One slot's change. Stacks are copies; editing them changes nothing. */
    public record Change(InventoryHolder holder, int slot, ItemStack before, ItemStack after) {
        /**
         * The slot when the inventory picks it itself (a hopper inserting wherever there's room). Then
         * {@code before} and {@code after} describe only the items that leave or arrive: {@code EMPTY} to the stack
         * for items arriving, the stack to {@code EMPTY} for items leaving.
         */
        public static final int ANY_SLOT = -1;

        public Change {
            Objects.requireNonNull(holder, "holder; use InventoryHolder.Unknown.INSTANCE when nothing is known");
            before = Objects.requireNonNull(before, "before; use ItemStack.EMPTY").copy();
            after = Objects.requireNonNull(after, "after; use ItemStack.EMPTY").copy();
        }

        /** Items arriving somewhere the inventory chooses. */
        public static Change arriving(InventoryHolder holder, ItemStack items) {
            return new Change(holder, ANY_SLOT, ItemStack.EMPTY, items);
        }

        /** Items leaving from wherever the inventory takes them. */
        public static Change leaving(InventoryHolder holder, ItemStack items) {
            return new Change(holder, ANY_SLOT, items, ItemStack.EMPTY);
        }
    }

    /** Every slot change, in the order they happen. */
    public List<Change> getChanges() {
        return changes;
    }

    /** Every inventory this touches, without repeats: for a move, the source and the destination. */
    public Set<InventoryHolder> getHolders() {
        Set<InventoryHolder> out = new LinkedHashSet<>();
        for (Change c : changes) out.add(c.holder());
        return out;
    }

    /** The changes to one holder's inventory. */
    public List<Change> getChanges(InventoryHolder holder) {
        List<Change> out = new ArrayList<>();
        for (Change c : changes) if (c.holder().equals(holder)) out.add(c);
        return out;
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
