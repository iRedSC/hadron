package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * An existing stack is about to change in place, from {@link #getBefore()} to {@link #getAfter()}: ammo spent, a
 * reagent used, a tool charged, durability lost, an enchantment added, a renamed item. Cancel it and the stack stays as
 * it was, and nothing the change would have cost (levels, materials) is spent.
 *
 * <p><b>Core fires the leaves, plus this for anvil results.</b> Core doesn't fire it for every count or component
 * write, since most of those are on copies and simulations. It fires {@link ItemDamageEvent}, {@link ItemRepairEvent}
 * and {@link ItemEnchantEvent} at their chokepoints, and this event itself when a player takes an anvil result that
 * only renames or otherwise changes the item. Bridges fire it for their mod's enclosing action (a gun spending ammo, a
 * wand spending a reagent), once per action rather than per write. Listen here to hear all of them.
 */
public class ItemChangeEvent extends ItemEvent implements Cancellable {
    private final InventoryHolder holder;
    private final ItemStack before;
    private ItemStack after;
    private boolean cancelled;

    /**
     * @param holder what holds the stack: the player, the machine, or the block whose menu it sits in
     * @param before the stack as it is now (copied)
     * @param after the stack as it will be (copied)
     */
    public ItemChangeEvent(ServerLevel level, InventoryHolder holder, ItemStack before, ItemStack after, Actor actor) {
        super(level, Objects.requireNonNull(before, "before; use ItemStack.EMPTY").copy(), actor);
        this.holder = Objects.requireNonNull(holder,
                "holder; use InventoryHolder.Unknown.INSTANCE when nothing is known");
        this.before = getItem();
        this.after = Objects.requireNonNull(after, "after; use ItemStack.EMPTY").copy();
    }

    /** What holds the stack. */
    public InventoryHolder getHolder() {
        return holder;
    }

    /** The stack before the change. A copy; same as {@link #getItem()}. */
    public ItemStack getBefore() {
        return before;
    }

    /** The stack after the change. A copy; editing it changes nothing. */
    public ItemStack getAfter() {
        return after;
    }

    /** For subclasses whose result a listener can edit, such as the damage amount. */
    protected void setAfter(ItemStack after) {
        this.after = Objects.requireNonNull(after, "after").copy();
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
