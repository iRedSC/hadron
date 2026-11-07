package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An actor is about to turn inputs into outputs: a player taking a crafting, smithing, stonecutter, loom or
 * cartography result, a crafter block firing, or a mod's autocrafter, cutting board or forge ritual. Replaces Bukkit's
 * {@code CraftItemEvent}. Cancel it and nothing is consumed or made.
 *
 * <p>Core fires it for players and fake players at every vanilla workstation, and for the crafter block. A
 * shift-click fires once for each craft it takes, so cancelling stops the batch where it is. Bridges fire it for their
 * mod's crafting, with every input, output and cost.
 */
@FiredBy(core = PARTIAL,
        coreSees = "every vanilla workstation and the crafter",
        bridges = "the mod's crafting, with every input, output and cost")
public class ItemCraftEvent extends ItemEvent implements Cancellable {
    private final InventoryHolder holder;
    private final List<ItemStack> inputs;
    private final List<ItemStack> outputs;
    private final RecipeHolder<?> recipe;
    private boolean cancelled;

    /**
     * @param inputs what one craft uses up (copied)
     * @param outputs what one craft makes, the main result first (copied)
     * @param recipe the recipe, or null where there is none (a loom pattern, a cartography table)
     */
    public ItemCraftEvent(ServerLevel level, InventoryHolder holder, List<ItemStack> inputs, List<ItemStack> outputs,
            RecipeHolder<?> recipe, Actor actor) {
        super(level, first(outputs), actor);
        this.holder = Objects.requireNonNull(holder,
                "holder; use InventoryHolder.Unknown.INSTANCE when nothing is known");
        this.inputs = copies(inputs);
        this.outputs = copies(outputs);
        this.recipe = recipe;
    }

    static ItemStack first(List<ItemStack> stacks) {
        Objects.requireNonNull(stacks, "outputs");
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0).copy();
    }

    static List<ItemStack> copies(List<ItemStack> stacks) {
        return stacks.stream().filter(s -> !s.isEmpty()).map(ItemStack::copy).toList();
    }

    /** Where it's crafted: the crafting table, the crafter, the player for their own 2x2 grid. */
    public InventoryHolder getHolder() {
        return holder;
    }

    /** What one craft uses up, empty slots left out. Copies. */
    public List<ItemStack> getInputs() {
        return inputs;
    }

    /** What one craft makes, the main result first. Copies. */
    public List<ItemStack> getOutputs() {
        return outputs;
    }

    /** The main result; same as {@link #getItem()}. */
    public ItemStack getResult() {
        return getItem();
    }

    public Optional<RecipeHolder<?>> getRecipe() {
        return Optional.ofNullable(recipe);
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
