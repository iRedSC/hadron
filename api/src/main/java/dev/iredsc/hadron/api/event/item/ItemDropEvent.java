package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * An actor is about to put an item into the world as an item entity. Replaces Bukkit's {@code PlayerDropItemEvent}.
 * Cancel it and the item stays where it was.
 *
 * <p>Core fires it for:
 *
 * <ul>
 *   <li>players and fake players, on every path: the drop key, dragging out of a menu, a menu closing with an item on
 *       the cursor, creative drops. Cancelled, the item goes back into the player's inventory; if the inventory has
 *       no room for it, it drops anyway rather than vanish.
 *   <li>dispensers and droppers spitting an item out. Cancelled, it stays in the dispenser.
 *   <li>mobs throwing an item: an allay delivering, a villager sharing food or gifting a hero, a piglin bartering.
 *       Cancelled, the item goes into the mob's own inventory; what doesn't fit is thrown anyway rather than vanish.
 * </ul>
 *
 * Not here: items a block or mob drops when it breaks or dies (those are the break and death events'), and items a
 * mod ejects from its own storage, which bridges fire this for before taking the item out.
 */
public class ItemDropEvent extends ItemEvent implements Cancellable {
    private final Vec3 position;
    private boolean cancelled;

    /**
     * @param item what drops; the live stack when the drop is a player's, so a listener may change the count
     */
    public ItemDropEvent(ServerLevel level, Vec3 position, ItemStack item, Actor actor) {
        super(level, item, actor);
        this.position = Objects.requireNonNull(position, "position");
    }

    /** Where the item appears. */
    public Vec3 getPosition() {
        return position;
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
