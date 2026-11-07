package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An item is about to be used in the air: eating or drinking starts, a bow is drawn, a pearl or snowball is thrown, a
 * wand casts, a mod's monitor links a drone. Replaces Bukkit's {@code PlayerInteractEvent} with
 * {@code RIGHT_CLICK_AIR}. Cancel it and nothing happens: nothing is thrown or spent, and the player's screen is
 * corrected.
 *
 * <p>Core fires it for players and fake players on every use, and for mobs that start using an item over time
 * (skeletons drawing bows, wandering traders drinking). Witches drink with their own code and are not seen. Using an
 * item on a block or an entity is the block's or entity's use event, not this.
 */
@FiredBy(core = FULL)
public class ItemUseEvent extends ItemEvent implements Cancellable {
    private final LivingEntity user;
    private final InteractionHand hand;
    private boolean cancelled;

    public ItemUseEvent(ServerLevel level, LivingEntity user, InteractionHand hand, ItemStack item, Actor actor) {
        super(level, item, actor);
        this.user = Objects.requireNonNull(user, "user");
        this.hand = Objects.requireNonNull(hand, "hand");
    }

    /** Who holds the item: a player, a fake player or a mob. {@link #getActor()} says who is behind them. */
    public LivingEntity getUser() {
        return user;
    }

    public InteractionHand getHand() {
        return hand;
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
