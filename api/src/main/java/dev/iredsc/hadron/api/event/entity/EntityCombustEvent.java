package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.world.entity.Entity;

/**
 * An entity that isn't burning is being set on fire: lava, fire, a flaming arrow, fire aspect, a spell. Fired once
 * when it catches, not again while fire keeps it burning. Cancel it and it doesn't catch.
 */
public class EntityCombustEvent extends EntityEvent implements Cancellable {
    private int ticks;
    private boolean cancelled;

    public EntityCombustEvent(Entity entity, int ticks, Actor actor) {
        super(entity, actor);
        this.ticks = ticks;
    }

    /** How long it will burn, in ticks (20 a second). */
    public int getDuration() {
        return ticks;
    }

    public void setDuration(int ticks) {
        if (ticks <= 0) throw new IllegalArgumentException("Burn duration must be at least 1 tick, got " + ticks
                + "; to stop it catching fire use setCancelled(true)");
        this.ticks = ticks;
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
