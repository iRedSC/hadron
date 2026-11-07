package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to be knocked back by a hit or a blast. The actor is whoever dealt the hit. Cancel it and
 * the entity isn't moved; the damage, if any, still lands. {@link #setStrength} scales the push, and
 * {@link #getNewVelocity()} always reflects the current strength.
 */
@FiredBy(core = FULL)
public class EntityKnockbackEvent extends EntityVelocityEvent {
    private final double ratioX;
    private final double ratioZ;
    private float strength;

    public EntityKnockbackEvent(LivingEntity entity, float strength, double ratioX, double ratioZ, Actor actor) {
        super(entity, entity.getDeltaMovement(), entity.getDeltaMovement(), actor);
        this.strength = strength;
        this.ratioX = ratioX;
        this.ratioZ = ratioZ;
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** How hard the push is before knockback resistance; 0.4 is an unenchanted hit. */
    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        if (!(strength >= 0) || Float.isInfinite(strength)) {
            throw new IllegalArgumentException("Knockback strength must be a finite number of at least 0, got "
                    + strength + "; to stop the knockback use setCancelled(true)");
        }
        this.strength = strength;
    }

    /** The velocity the entity will have, worked out as vanilla does from the current strength. */
    @Override
    public Vec3 getNewVelocity() {
        LivingEntity entity = getEntity();
        double s = strength * (1.0 - entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        Vec3 old = getOldVelocity();
        if (s <= 0 || ratioX * ratioX + ratioZ * ratioZ < 1.0E-5F) return old;
        Vec3 push = new Vec3(ratioX, 0, ratioZ).normalize().scale(s);
        return new Vec3(old.x / 2 - push.x, entity.onGround() ? Math.min(0.4, old.y / 2 + s) : old.y,
                old.z / 2 - push.z);
    }

    /**
     * Knockback is set by strength, not by a final velocity: use {@link #setStrength}, or cancel this and push the
     * entity yourself.
     */
    @Override
    public void setNewVelocity(Vec3 velocity) {
        throw new UnsupportedOperationException("Knockback is set by strength: call setStrength(...) instead, or "
                + "cancel the knockback and call entity.setDeltaMovement(...) yourself");
    }
}
