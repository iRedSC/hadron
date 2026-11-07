package dev.iredsc.hadron.api.event.entity;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity is entering the world for the first time: a mob spawning, an egg hatching, a baby being born, a summon, a
 * fish released from a bucket, an arrow fired, an item frame hung. Not fired for entities loading with their chunk,
 * moving between dimensions, or converting into another (that is {@link EntityTransformEvent}), nor for dropped
 * items, experience orbs and falling blocks, whose contents were already taken from somewhere: refusing them would
 * destroy it. Cancel it and the entity never appears; a spawn egg or item frame used by a player is not spent.
 */
@FiredBy(core = FULL)
public class EntitySpawnEvent extends EntityEvent implements Cancellable {
    private final @Nullable MobSpawnType reason;
    private boolean cancelled;

    /** {@code reason} is null for entities that aren't mobs and mobs spawned without one. */
    public EntitySpawnEvent(Entity entity, @Nullable MobSpawnType reason, Actor actor) {
        super(entity, actor);
        this.reason = reason;
    }

    /**
     * Why a mob spawned: natural, spawner, spawn egg, breeding, summoned, bucket (released from capture) and so on.
     * Empty for entities that aren't mobs (arrows, items, vehicles) and for mobs spawned without a reason.
     */
    public Optional<MobSpawnType> getSpawnReason() {
        return Optional.ofNullable(reason);
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
