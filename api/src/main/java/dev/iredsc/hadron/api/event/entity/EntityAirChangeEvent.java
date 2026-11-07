package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity's air supply is about to change: running out underwater, refilling at the surface, a mod's breathing
 * gear. Cancel it and the air stays where it was. Fires every tick an entity is underwater, so keep listeners light.
 */
@FiredBy(core = FULL)
public class EntityAirChangeEvent extends EntityEvent implements Cancellable {
    private final int oldAmount;
    private int amount;
    private boolean cancelled;

    public EntityAirChangeEvent(Entity entity, int oldAmount, int amount, Actor actor) {
        super(entity, actor);
        this.oldAmount = oldAmount;
        this.amount = amount;
    }

    /** The air it had, in ticks; 300 is full for most entities. */
    public int getOldAmount() {
        return oldAmount;
    }

    /** The air it will have, in ticks. */
    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
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
