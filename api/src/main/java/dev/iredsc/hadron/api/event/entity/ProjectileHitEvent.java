package dev.iredsc.hadron.api.event.entity;

import java.util.List;
import java.util.ArrayList;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A projectile is about to hit a block or an entity. The actor is the projectile, which carries who fired it. Cancel
 * it and the projectile passes through as if nothing was there.
 *
 * <p>A hitscan weapon with no projectile entity (a mod's gun) is fired by its bridge: then {@link #getEntity()} is
 * the shooter.
 */
@FiredBy(core = PARTIAL,
        coreSees = "projectile entities",
        bridges = "hitscan weapons with no projectile, with the shooter as the entity")
public class ProjectileHitEvent extends EntityEvent implements Cancellable {
    private final HitResult hit;
    private boolean cancelled;

    public ProjectileHitEvent(Entity projectile, HitResult hit, Actor actor) {
        super(projectile, actor);
        this.hit = Objects.requireNonNull(hit, "hit");
    }

    /** The projectile, when there is one; empty for a bridge's hitscan shot. */
    public Optional<Projectile> getProjectile() {
        return getEntity() instanceof Projectile p ? Optional.of(p) : Optional.empty();
    }

    /** The entity hit, if it hit one. */
    public Optional<Entity> getHitEntity() {
        return hit instanceof EntityHitResult e ? Optional.of(e.getEntity()) : Optional.empty();
    }

    /** The block hit, if it hit one. */
    public Optional<BlockPos> getHitBlock() {
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
                ? Optional.of(b.getBlockPos()) : Optional.empty();
    }

    /** The face of the block hit, if it hit one. */
    public Optional<Direction> getHitBlockFace() {
        return hit instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
                ? Optional.of(b.getDirection()) : Optional.empty();
    }

    /** Exactly where it hit. */
    public Vec3 getHitPosition() {
        return hit.getLocation();
    }

    /** Minecraft's own hit result, for anything else. */
    public HitResult getHitResult() {
        return hit;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Where the projectile (or a hitscan shot's shooter) is, then where it hits: the point, the block and the entity
     * hit, each when there is one.
     */
    @Override
    public Places getPlaces() {
        List<Place> hit = new ArrayList<>();
        hit.add(Place.Spot.at(getLevel(), getHitPosition()));
        getHitBlock().ifPresent(block -> hit.add(Place.Spot.at(getLevel(), block)));
        getHitEntity().ifPresent(entity -> hit.add(Place.of(entity)));
        return super.getPlaces().and(hit);
    }
}
