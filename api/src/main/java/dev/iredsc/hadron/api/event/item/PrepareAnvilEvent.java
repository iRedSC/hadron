package dev.iredsc.hadron.api.event.item;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An anvil worked out what it would make from its inputs, and is about to show it. Nothing has been spent yet; taking
 * the result later fires {@link ItemEnchantEvent}, {@link ItemRepairEvent} or {@link ItemChangeEvent}. Set the result
 * or cost to change the offer. Cancel it and the anvil offers nothing.
 */
@FiredBy(core = FULL)
public class PrepareAnvilEvent extends ItemEvent implements Cancellable {
    private final ServerPlayer viewer;
    private final InventoryHolder holder;
    private final AnvilMenu menu;
    private final ItemStack left;
    private final ItemStack right;
    private final @Nullable String renameText;
    private ItemStack result;
    private int repairCost;
    private boolean cancelled;

    public PrepareAnvilEvent(ServerLevel level, ServerPlayer viewer, InventoryHolder holder, AnvilMenu menu,
            ItemStack left, ItemStack right, @Nullable String renameText, ItemStack result, int repairCost, Actor actor) {
        super(level, left.copy(), actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.holder = Objects.requireNonNull(holder, "holder");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.left = left.copy();
        this.right = right.copy();
        this.renameText = renameText;
        this.result = result.copy();
        this.repairCost = repairCost;
    }

    /** Who is using the anvil. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** The anvil block. */
    public InventoryHolder getHolder() {
        return holder;
    }

    public AnvilMenu getMenu() {
        return menu;
    }

    /** The item being worked on. A copy. */
    public ItemStack getLeft() {
        return left;
    }

    /** The material, book or second item. A copy. */
    public ItemStack getRight() {
        return right;
    }

    /** The name typed, or null if none. */
    public @Nullable String getRenameText() {
        return renameText;
    }

    /** What the anvil offers. A copy; use {@link #setResult} to change it. */
    public ItemStack getResult() {
        return result.copy();
    }

    /** Offer this instead; {@code ItemStack.EMPTY} offers nothing. */
    public void setResult(ItemStack result) {
        this.result = Objects.requireNonNull(result, "result; use ItemStack.EMPTY to offer nothing").copy();
    }

    /** Experience levels taking the result costs. */
    public int getRepairCost() {
        return repairCost;
    }

    /** At least 1: vanilla won't hand over a result that costs nothing. */
    public void setRepairCost(int repairCost) {
        if (repairCost < 1) {
            throw new IllegalArgumentException("An anvil result costs at least 1 level (got " + repairCost + "); "
                    + "vanilla refuses to hand over one that costs 0. To offer nothing, cancel the event or "
                    + "setResult(ItemStack.EMPTY)");
        }
        this.repairCost = repairCost;
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
