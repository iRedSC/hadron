package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An existing stack is about to change in place, from {@link #getBefore()} to {@link #getAfter()}: ammo spent, a
 * reagent used, a tool charged, durability lost, an enchantment added, a renamed item. Cancel it and the stack stays as
 * it was, and nothing the change would have cost (levels, materials) is spent.
 *
 * <p><b>Core fires the leaves, plus this for anvil and grindstone results.</b> Core doesn't fire it for every count or
 * component write, since most of those are on copies and simulations. It fires {@link ItemDamageEvent}, {@link
 * ItemRepairEvent} and {@link ItemEnchantEvent} at their chokepoints, and this event itself when a player takes an
 * anvil or grindstone result that only renames or otherwise changes the item. Bridges fire it for their mod's enclosing
 * action (a gun spending ammo, a wand spending a reagent), once per action rather than per write. Listen here to hear
 * all of them.
 */
@FiredBy(core = PARTIAL,
        coreSees = "anvil and grindstone results",
        bridges = "the enclosing action that changes a stack in place (a gun spending ammo, a wand spending a "
                + "reagent), once per action")
public class ItemChangeEvent extends ItemEvent implements Cancellable {
    private final InventoryHolder holder;
    private EventStack.Copy after;
    private boolean cancelled;

    /**
     * @param holder what holds the stack: the player, the machine, or the block whose menu it sits in
     * @param before the stack as it is now (copied)
     * @param after the stack as it will be (copied)
     */
    public ItemChangeEvent(ServerLevel level, InventoryHolder holder, ItemStack before, ItemStack after, Actor actor) {
        super(level, EventStack.copyOf(before, "before"), actor);
        this.holder = Objects.requireNonNull(holder,
                "holder; use InventoryHolder.Unknown.INSTANCE when nothing is known");
        this.after = EventStack.copyOf(after, "after");
    }

    /** What holds the stack. */
    public InventoryHolder getHolder() {
        return holder;
    }

    /** The stack before the change; same as {@link #getItem()}. A new copy each call, so editing it changes nothing. */
    public ItemStack getBefore() {
        return getItem();
    }

    /** The stack after the change. A new copy each call, so editing it changes nothing. */
    public ItemStack getAfter() {
        return after.get();
    }

    /** For subclasses whose result a listener can edit, such as the damage amount. */
    protected void setAfter(ItemStack after) {
        this.after = EventStack.copyOf(after, "after");
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** Where the inventory holding the stack is ({@link #getHolder()}). */
    @Override
    public Places getPlaces() {
        return Places.of(Place.of(getHolder()));
    }
}
