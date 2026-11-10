package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player, or a vehicle someone is driving, has moved into another block. Fired at most once per tick per entity,
 * only when the block changes, so it is cheap to listen to. Cancel it and the entity is put back where it was in
 * the block it left. A player riding something is covered by the vehicle's event, not their own.
 */
@FiredBy(core = FULL)
public class EntityMoveEvent extends EntityEvent implements Cancellable {
    private final Vec3 from;
    private final Vec3 to;
    private boolean cancelled;

    public EntityMoveEvent(Entity entity, Vec3 from, Vec3 to, Actor actor) {
        super(entity, actor);
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
    }

    /** Where it was, in the block it left. */
    public Vec3 getFrom() {
        return from;
    }

    /** Where it is now, in the block it entered. */
    public Vec3 getTo() {
        return to;
    }

    public BlockPos getFromBlock() {
        return BlockPos.containing(from);
    }

    public BlockPos getToBlock() {
        return BlockPos.containing(to);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** The block it leaves, then the block it enters. */
    @Override
    public Places getPlaces() {
        return Places.of(Place.Spot.at(getLevel(), getFromBlock()), Place.Spot.at(getLevel(), getToBlock()));
    }
}
