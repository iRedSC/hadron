package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
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
 * A machine is about to finish processing items: a furnace, smoker or blast furnace smelting, a campfire cooking, a
 * brewing stand brewing, a mod's crusher or a Create fan. Replaces Bukkit's {@code FurnaceSmeltEvent} and
 * {@code BrewEvent}. Cancel it and the inputs stay and nothing is made; the machine starts that item over. Fuel
 * already burnt stays burnt.
 *
 * <p>Core fires it for every vanilla furnace, campfire and brewing stand. Bridges fire it for their mod's machines,
 * including items processed in the world or on belts, with every input, catalyst and output.
 */
@FiredBy(core = PARTIAL,
        coreSees = "furnaces, campfires, brewing stands",
        bridges = "the mod's machines, including items processed in the world or on belts")
public class ItemSmeltEvent extends ItemEvent implements Cancellable {
    private final InventoryHolder holder;
    private final List<EventStack.Copy> inputs;
    private final List<EventStack.Copy> catalysts;
    private final List<EventStack.Copy> outputs;
    private final @Nullable RecipeHolder<?> recipe;
    private boolean cancelled;

    /**
     * @param inputs what this step uses up (copied)
     * @param catalysts what it needs but doesn't use up, or turns into something else (copied); may be empty
     * @param outputs what it makes, the main result first (copied)
     * @param recipe the recipe, or null where there is none (brewing)
     */
    public ItemSmeltEvent(ServerLevel level, InventoryHolder holder, List<ItemStack> inputs, List<ItemStack> catalysts,
            List<ItemStack> outputs, @Nullable RecipeHolder<?> recipe, Actor actor) {
        super(level, ItemCraftEvent.first(outputs), actor);
        this.holder = Objects.requireNonNull(holder,
                "holder; use InventoryHolder.Unknown.INSTANCE when nothing is known");
        this.inputs = ItemCraftEvent.copies(inputs, "inputs");
        this.catalysts = ItemCraftEvent.copies(catalysts, "catalysts");
        this.outputs = ItemCraftEvent.copies(outputs, "outputs");
        this.recipe = recipe;
    }

    /** The machine, or the world position for items processed on the ground. */
    public InventoryHolder getHolder() {
        return holder;
    }

    /** What this step uses up. Copies. */
    public List<ItemStack> getInputs() {
        return EventStack.getAll(inputs);
    }

    /** The first input; for a furnace, the item being smelted. */
    public ItemStack getSource() {
        return inputs.isEmpty() ? ItemStack.EMPTY : inputs.get(0).get();
    }

    /** What it needs but doesn't use up. Copies. */
    public List<ItemStack> getCatalysts() {
        return EventStack.getAll(catalysts);
    }

    /** What it makes, the main result first. Copies. */
    public List<ItemStack> getOutputs() {
        return EventStack.getAll(outputs);
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

    /** Where the inventory holding the stack is ({@link #getHolder()}). */
    @Override
    public Places getPlaces() {
        return Place.of(getHolder());
    }
}
