package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * What an item frame holds is changing: an item put in, knocked out, or turned. Paper's
 * {@code PlayerItemFrameChangeEvent}, for any actor: a player, an arrow, a mob. Cancel it and the frame keeps what it
 * had, the way it had it, and nothing drops or is spent. Breaking the frame itself is {@link HangingBreakEvent}.
 */
@FiredBy(core = FULL)
public class ItemFrameChangeEvent extends EntityEvent implements Cancellable {
    /** What is happening to the frame's item. Paper's names. */
    public enum ItemFrameChangeAction {
        /** An item goes into an empty frame. */
        PLACE,
        /** The item is knocked out: a hit, or an arrow. */
        REMOVE,
        /** The item turns one step. */
        ROTATE
    }

    private final ItemFrameChangeAction action;
    private final EventStack.Copy itemStack;
    private boolean cancelled;

    public ItemFrameChangeEvent(ItemFrame frame, ItemFrameChangeAction action, ItemStack itemStack, Actor actor) {
        super(frame, actor);
        this.action = Objects.requireNonNull(action, "action");
        this.itemStack = EventStack.copyOf(itemStack, "itemStack (the item placed, removed or turned)");
    }

    @Override
    public ItemFrame getEntity() {
        return (ItemFrame) super.getEntity();
    }

    public ItemFrameChangeAction getAction() {
        return action;
    }

    /** The item going in, coming out, or turning: one item, a copy, so changing it changes nothing. */
    public ItemStack getItemStack() {
        return itemStack.get();
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
