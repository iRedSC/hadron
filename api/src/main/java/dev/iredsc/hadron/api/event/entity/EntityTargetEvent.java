package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A mob is about to pick a target, or drop one. {@link #getEntity()} is the mob choosing, and the actor is that mob.
 * Cancel it and the mob keeps its current target; {@link #setTarget} makes it pick another. Turret blocks are fired
 * by their mod's bridge.
 */
@FiredBy(core = PARTIAL, coreSees = "mobs", bridges = "turret blocks")
public class EntityTargetEvent extends EntityEvent implements Cancellable {
    private @Nullable LivingEntity target;
    private boolean cancelled;

    public EntityTargetEvent(LivingEntity entity, @Nullable LivingEntity target, Actor actor) {
        super(entity, actor);
        this.target = target;
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** What it will target; empty when it is giving up its target. */
    public Optional<LivingEntity> getTarget() {
        return Optional.ofNullable(target);
    }

    /** Make it target this instead; null to make it target nothing. */
    public void setTarget(@Nullable LivingEntity target) {
        this.target = target;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** Where the mob is, then where its new target is, if any. */
    @Override
    public Places getPlaces() {
        Places own = super.getPlaces();
        return getTarget().map(target -> own.and(Place.of(target))).orElse(own);
    }
}
