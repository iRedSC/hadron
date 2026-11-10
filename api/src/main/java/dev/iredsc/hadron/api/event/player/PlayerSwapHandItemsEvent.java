package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A player pressed the swap-hands key. Change what ends up in each hand, or cancel so nothing moves. */
@FiredBy(core = FULL)
public class PlayerSwapHandItemsEvent extends PlayerEvent implements Cancellable {
    private EventStack.Copy mainHandItem;
    private EventStack.Copy offHandItem;
    private boolean cancelled;

    public PlayerSwapHandItemsEvent(ServerPlayer player, ItemStack mainHandItem, ItemStack offHandItem) {
        super(player);
        this.mainHandItem = EventStack.copyOf(mainHandItem, "mainHandItem");
        this.offHandItem = EventStack.copyOf(offHandItem, "offHandItem");
    }

    /** What will be in the main hand afterwards. A new copy each call; change it with {@link #setMainHandItem}. */
    public ItemStack getMainHandItem() {
        return mainHandItem.get();
    }

    /** Put this in the main hand instead (copied); {@code ItemStack.EMPTY} leaves it empty. */
    public void setMainHandItem(ItemStack item) {
        this.mainHandItem = EventStack.copyOf(item, "item (use ItemStack.EMPTY for an empty hand)");
    }

    /** What will be in the off hand afterwards. A new copy each call; change it with {@link #setOffHandItem}. */
    public ItemStack getOffHandItem() {
        return offHandItem.get();
    }

    /** Put this in the off hand instead (copied); {@code ItemStack.EMPTY} leaves it empty. */
    public void setOffHandItem(ItemStack item) {
        this.offHandItem = EventStack.copyOf(item, "item (use ItemStack.EMPTY for an empty hand)");
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
