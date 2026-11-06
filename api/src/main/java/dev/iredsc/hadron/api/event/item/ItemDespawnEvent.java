package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * An item entity has lain on the ground for its whole lifespan and is about to vanish. Cancel it and it stays for
 * another full lifespan (five minutes for most items). The actor is nature.
 */
public class ItemDespawnEvent extends ItemEvent implements Cancellable {
    private final ItemEntity entity;
    private boolean cancelled;

    public ItemDespawnEvent(ServerLevel level, ItemEntity entity, Actor actor) {
        super(level, Objects.requireNonNull(entity, "entity").getItem(), actor);
        this.entity = entity;
    }

    /** The item entity about to vanish. */
    public ItemEntity getItemEntity() {
        return entity;
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
