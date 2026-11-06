package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

/**
 * An entity is about to start riding another: a player boarding a boat or horse, a mob entering a minecart, a mod's
 * vehicle or a player carrying another. {@link #getEntity()} is the rider. Cancel it and nobody mounts.
 */
public class EntityMountEvent extends EntityEvent implements Cancellable {
    private final Entity mount;
    private boolean cancelled;

    public EntityMountEvent(Entity rider, Entity mount, Actor actor) {
        super(rider, actor);
        this.mount = Objects.requireNonNull(mount, "mount");
    }

    /** What is being ridden. */
    public Entity getMount() {
        return mount;
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
