package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Something is about to finish eating or drinking an item: food, a potion, milk, honey. Replaces Bukkit's
 * {@code PlayerItemConsumeEvent}. Cancel it and the item isn't consumed and has no effect; the player's screen is
 * corrected.
 *
 * <p>Core fires it for players, fake players and every mob that eats or drinks through the item (a fox eating
 * berries, a wandering trader drinking). Witches and pandas eat with their own code and are not seen.
 * {@link #getItem()} is the live stack; don't change it.
 */
public class ItemConsumeEvent extends ItemEvent implements Cancellable {
    private final LivingEntity consumer;
    private boolean cancelled;

    public ItemConsumeEvent(ServerLevel level, LivingEntity consumer, ItemStack item, Actor actor) {
        super(level, item, actor);
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    /** Who eats or drinks it. */
    public LivingEntity getConsumer() {
        return consumer;
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
