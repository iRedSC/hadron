package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A crafting grid worked out what its ingredients make: a player's own 2x2 grid, a crafting table (or a mod's menu
 * built on vanilla's crafting grid), or a crafter about to craft or showing its result to someone looking in. Nothing
 * has been spent yet; taking the result fires {@link ItemCraftEvent}.
 *
 * <p>Set the result to change what the grid offers, or {@code ItemStack.EMPTY} to forbid it. Not cancellable, as in
 * Bukkit. A result offered where no recipe matches uses up one of each ingredient when taken.
 *
 * <p>At a crafter, only a matching recipe's result can be changed or emptied; with no recipe matching, the crafter
 * makes nothing and this event doesn't fire for it.
 */
@FiredBy(core = PARTIAL,
        coreSees = "the 2x2 grid, crafting tables, the crafter, and mods' menus built on vanilla's crafting grid",
        bridges = "the mod's own crafting grids, when they work out a result; honour the result a listener sets")
public class PrepareItemCraftEvent extends ItemEvent {
    private final InventoryHolder holder;
    private final @Nullable AbstractContainerMenu menu;
    private final @Nullable ServerPlayer viewer;
    private final List<EventStack.Copy> matrix;
    private final @Nullable RecipeHolder<?> recipe;
    private EventStack.Copy result;

    /**
     * @param menu the menu showing the grid, or null for a crafter crafting on its own
     * @param viewer the player looking at the grid, or null for a crafter crafting on its own
     * @param matrix the grid's slots, in order, empty ones included
     * @param recipe the recipe that matched, or null if none did
     */
    public PrepareItemCraftEvent(ServerLevel level, InventoryHolder holder, @Nullable AbstractContainerMenu menu,
            @Nullable ServerPlayer viewer, List<ItemStack> matrix, @Nullable RecipeHolder<?> recipe, ItemStack result, Actor actor) {
        super(level, EventStack.copyOf(result, "result"), actor);
        this.holder = Objects.requireNonNull(holder, "holder");
        this.menu = menu;
        this.viewer = viewer;
        this.matrix = EventStack.copiesOf(matrix, "matrix");
        this.recipe = recipe;
        this.result = EventStack.copyOf(result, "result");
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
        return EventStack.getAll(matrix);
    }

    /** The recipe the ingredients match; empty when none does. */
    public Optional<RecipeHolder<?>> getRecipe() {
        return Optional.ofNullable(recipe);
    }

    /** What the grid offers. A copy; use {@link #setResult} to change it. */
    public ItemStack getResult() {
        return result.get();
    }

    /** Offer this instead; {@code ItemStack.EMPTY} offers nothing, so the ingredients can't be crafted. */
    public void setResult(ItemStack result) {
        this.result = EventStack.copyOf(result, "result");
    }

    /** Where the inventory holding the stack is ({@link #getHolder()}). */
    @Override
    public Places getPlaces() {
        return Places.of(Place.of(getHolder()));
    }
}
