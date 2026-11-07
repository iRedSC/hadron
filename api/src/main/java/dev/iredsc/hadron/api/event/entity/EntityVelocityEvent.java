package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * An actor is about to push, pull or launch an entity. Cancel it and the entity's velocity is left alone.
 *
 * <p>Core fires it whenever something other than the entity sets its velocity while acting: a command, an
 * explosion, another entity, a machine, a mod's code with an actor on the stack. An entity's own movement and physics
 * never fire it. Knockback from a hit is {@link EntityKnockbackEvent} instead, fired once. Pushes a mod makes with
 * nothing on the stack are fired by that mod's bridge.
 */
public class EntityVelocityEvent extends EntityEvent implements Cancellable {
    private final Vec3 oldVelocity;
    private Vec3 newVelocity;
    private boolean cancelled;

    public EntityVelocityEvent(Entity entity, Vec3 oldVelocity, Vec3 newVelocity, Actor actor) {
        super(entity, actor);
        this.oldVelocity = Objects.requireNonNull(oldVelocity, "oldVelocity");
        this.newVelocity = Objects.requireNonNull(newVelocity, "newVelocity");
    }

    /** The velocity before the push, in blocks per tick. */
    public Vec3 getOldVelocity() {
        return oldVelocity;
    }

    /** The velocity after the push, in blocks per tick. */
    public Vec3 getNewVelocity() {
        return newVelocity;
    }

    public void setNewVelocity(Vec3 velocity) {
        this.newVelocity = Objects.requireNonNull(velocity, "velocity; to leave it unchanged use setCancelled(true)");
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
