package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * A crafting grid worked out what its ingredients make: a player's own 2x2 grid, a crafting table (or a mod's menu
 * built on vanilla's crafting grid), or a crafter about to craft or showing its result to someone looking in. Nothing
 * has been spent yet; taking the result fires {@link ItemCraftEvent}.
 *
 * <p>Set the result to change what the grid offers, or {@code ItemStack.EMPTY} to forbid it. Not cancellable, as in
 * Bukkit. A result offered where no recipe matches uses up one of each ingredient when taken.
 */
public class PrepareItemCraftEvent extends ItemEvent {
    private final InventoryHolder holder;
    private final AbstractContainerMenu menu;
    private final ServerPlayer viewer;
    private final List<ItemStack> matrix;
    private final RecipeHolder<?> recipe;
    private ItemStack result;

    /**
     * @param menu the menu showing the grid, or null for a crafter crafting on its own
     * @param viewer the player looking at the grid, or null for a crafter crafting on its own
     * @param matrix the grid's slots, in order, empty ones included
     * @param recipe the recipe that matched, or null if none did
     */
    public PrepareItemCraftEvent(ServerLevel level, InventoryHolder holder, AbstractContainerMenu menu,
            ServerPlayer viewer, List<ItemStack> matrix, RecipeHolder<?> recipe, ItemStack result, Actor actor) {
        super(level, Objects.requireNonNull(result, "result; use ItemStack.EMPTY").copy(), actor);
        this.holder = Objects.requireNonNull(holder, "holder");
        this.menu = menu;
        this.viewer = viewer;
        List<ItemStack> copies = new ArrayList<>(matrix.size());
        for (ItemStack stack : matrix) copies.add(stack.copy());
        this.matrix = Collections.unmodifiableList(copies);
        this.recipe = recipe;
        this.result = result.copy();
    }

    /** What holds the grid: the player, the crafting table block, the crafter block. */
    public InventoryHolder getHolder() {
        return holder;
    }

    /** The menu showing the grid; empty when a crafter crafts with nobody looking. */
    public Optional<AbstractContainerMenu> getMenu() {
        return Optional.ofNullable(menu);
    }

    /** The player looking at the grid; empty when a crafter crafts on its own. */
    public Optional<ServerPlayer> getViewer() {
        return Optional.ofNullable(viewer);
    }

    /** Copies of the grid's slots, left to right, top to bottom, empty slots included. Bukkit's matrix. */
    public List<ItemStack> getMatrix() {
        return matrix;
    }

    /** The recipe the ingredients match; empty when none does. */
    public Optional<RecipeHolder<?>> getRecipe() {
        return Optional.ofNullable(recipe);
    }

    /** What the grid offers. A copy; use {@link #setResult} to change it. */
    public ItemStack getResult() {
        return result.copy();
    }

    /** Offer this instead; {@code ItemStack.EMPTY} offers nothing, so the ingredients can't be crafted. */
    public void setResult(ItemStack result) {
        this.result = Objects.requireNonNull(result, "result; use ItemStack.EMPTY to offer nothing").copy();
    }
}
