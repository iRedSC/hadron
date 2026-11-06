package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.world.entity.ExperienceOrb;

/**
 * An experience orb is about to be collected. {@link #getEntity()} is the orb; the actor is the collector: a player,
 * a fake player, or (fired by its bridge) a machine storing experience. Cancel it and the orb stays in the world.
 */
public class ExperiencePickupEvent extends EntityEvent implements Cancellable {
    private boolean cancelled;

    public ExperiencePickupEvent(ExperienceOrb orb, Actor actor) {
        super(orb, actor);
    }

    @Override
    public ExperienceOrb getEntity() {
        return (ExperienceOrb) super.getEntity();
    }

    /** Experience points in the orb. */
    public int getAmount() {
        return getEntity().getValue();
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
