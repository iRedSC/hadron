package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * {@link EntityVelocityEvent} for anything that pushes, pulls or launches an entity: a command, an explosion,
 * another entity, a machine. Asked at {@code Entity.setDeltaMovement}, but only when a plugin listens and something
 * other than the entity is acting, so an entity's own movement and physics never fire it. Knockback is reported as
 * {@code EntityKnockbackEvent} instead, once. Server thread only.
 */
public final class EntityVelocities {
    private EntityVelocities() {}

    /** Above zero inside a knockback, which its own event reports. */
    static int knockback;

    /** The velocity to set: {@code next} itself, a listener's change, or the current one when cancelled. */
    public static Vec3 velocity(Entity entity, Vec3 next) {
        if (knockback > 0 || !Hooks.listens(EntityVelocityEvent.class)) return next;
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !entity.isAddedToLevel()) {
            return next;
        }
        Actor actor = Actors.currentOr(null);
        if (actor == null || EntityActors.isSelf(actor, entity)) return next;
        Vec3 current = entity.getDeltaMovement();
        if (current.equals(next)) return next;
        EntityVelocityEvent event = Hooks.post(new EntityVelocityEvent(entity, current, next, actor));
        return event.isCancelled() ? current : event.getNewVelocity();
    }

    public static void enterKnockback() {
        knockback++;
    }

    public static void exitKnockback() {
        knockback--;
    }
}
