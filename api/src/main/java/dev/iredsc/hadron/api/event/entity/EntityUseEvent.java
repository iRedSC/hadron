package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * An actor is right-clicking an entity: trading with a villager, saddling a horse, dyeing a sheep, opening a
 * minecart chest, putting an item in a frame. Bukkit's {@code PlayerInteractEntityEvent}. Cancel it and nothing
 * happens and no item is used. Fired once per hand.
 */
public class EntityUseEvent extends EntityEvent implements Cancellable {
    private final InteractionHand hand;
    private final ItemStack item;
    private boolean cancelled;

    public EntityUseEvent(Entity entity, InteractionHand hand, ItemStack item, Actor actor) {
        super(entity, actor);
        this.hand = Objects.requireNonNull(hand, "hand");
        this.item = Objects.requireNonNull(item, "item; use ItemStack.EMPTY for an empty hand");
    }

    /**
     * The player who clicked, if a player is behind it and online: directly, or the owner of the fake player that
     * did. Same as {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }

    public InteractionHand getHand() {
        return hand;
    }

    /** What was in that hand; empty stack for nothing. */
    public ItemStack getItem() {
        return item;
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
