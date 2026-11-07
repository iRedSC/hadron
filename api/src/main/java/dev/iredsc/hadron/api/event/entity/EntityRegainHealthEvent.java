package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.world.entity.LivingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to heal: natural regeneration, a potion, a spell, a machine. The actor is the healer, or
 * {@link Actor.Nature} when the entity heals on its own. Cancel it and no health is restored. Absorption hearts are
 * not this event yet.
 */
@FiredBy(core = FULL)
public class EntityRegainHealthEvent extends EntityEvent implements Cancellable {
    private float amount;
    private boolean cancelled;

    public EntityRegainHealthEvent(LivingEntity entity, float amount, Actor actor) {
        super(entity, actor);
        this.amount = amount;
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** Health points to restore; two is one heart. */
    public float getAmount() {
        return amount;
    }

    public void setAmount(float amount) {
        if (!(amount >= 0) || Float.isInfinite(amount)) {
            throw new IllegalArgumentException("Heal amount must be a finite number of at least 0, got " + amount
                    + "; to stop the healing use setCancelled(true)");
        }
        this.amount = amount;
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
