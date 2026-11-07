package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.world.entity.ExperienceOrb;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An experience orb is about to be collected. {@link #getEntity()} is the orb; the actor is the collector: a player,
 * a fake player, or (fired by its bridge) a machine storing experience. Cancel it and the orb stays in the world.
 */
@FiredBy(core = PARTIAL, coreSees = "players and fake players", bridges = "machines storing experience")
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
