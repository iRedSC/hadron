package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A player pressed the swap-hands key. Change what ends up in each hand, or cancel so nothing moves. */
@FiredBy(core = FULL)
public class PlayerSwapHandItemsEvent extends PlayerEvent implements Cancellable {
    private ItemStack mainHandItem;
    private ItemStack offHandItem;
    private boolean cancelled;

    public PlayerSwapHandItemsEvent(ServerPlayer player, ItemStack mainHandItem, ItemStack offHandItem) {
        super(player);
        this.mainHandItem = mainHandItem;
        this.offHandItem = offHandItem;
    }

    /** What will be in the main hand afterwards. */
    public ItemStack getMainHandItem() {
        return mainHandItem;
    }

    public void setMainHandItem(ItemStack item) {
        this.mainHandItem = Objects.requireNonNull(item, "Item cannot be null; use ItemStack.EMPTY for an empty hand");
    }

    /** What will be in the off hand afterwards. */
    public ItemStack getOffHandItem() {
        return offHandItem;
    }

    public void setOffHandItem(ItemStack item) {
        this.offHandItem = Objects.requireNonNull(item, "Item cannot be null; use ItemStack.EMPTY for an empty hand");
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
