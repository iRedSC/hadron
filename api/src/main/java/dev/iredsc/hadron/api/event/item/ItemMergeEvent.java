package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Two item entities on the ground are about to merge into one. Fired only when they really will merge (same item,
 * room in the stack). Cancel it and both stay as they are. The actor is nature.
 */
public class ItemMergeEvent extends ItemEvent implements Cancellable {
    private final ItemEntity entity;
    private final ItemEntity target;
    private boolean cancelled;

    public ItemMergeEvent(ServerLevel level, ItemEntity entity, ItemEntity target, Actor actor) {
        super(level, Objects.requireNonNull(entity, "entity").getItem(), actor);
        this.entity = entity;
        this.target = Objects.requireNonNull(target, "target");
    }

    /** The item entity that gives its items up and disappears if emptied. */
    public ItemEntity getItemEntity() {
        return entity;
    }

    /** The item entity that receives them. */
    public ItemEntity getTarget() {
        return target;
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
