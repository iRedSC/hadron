package dev.iredsc.hadron.api.event.world;

import java.util.ArrayList;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An explosion has worked out what it hits and is about to destroy and damage it. Remove positions from
 * {@link #blockList()} to spare those blocks, or entities from {@link #getEntities()} to spare them; cancel to spare
 * everything (the sound and particles still play). A blast spread over several ticks fires this once per batch.
 *
 * <p>The block changes it then makes are fired as block events inside the same {@link #getOperation() operation}, so
 * a logger can group them under this explosion.
 */
@FiredBy(core = PARTIAL,
        coreSees = "`Level.explode` and `Explosion`",
        bridges = "explosions the mod computes without vanilla's `Explosion`")
public class ExplosionEvent extends WorldEvent implements Cancellable {
    private final Explosion explosion;
    private final List<BlockPos> blocks;
    private final List<Entity> entities;
    private boolean cancelled;

    public ExplosionEvent(Actor actor, ServerLevel level, Explosion explosion, List<BlockPos> blocks,
            List<Entity> entities) {
        super(actor, level, around(explosion.center(), explosion.radius() * 2.0));
        this.explosion = explosion;
        this.blocks = blocks;
        this.entities = entities;
    }

    public Explosion getExplosion() {
        return explosion;
    }

    public Vec3 getLocation() {
        return explosion.center();
    }

    /** What exploded, such as primed TNT or a creeper; empty for a bed or a mod's blast with no entity. */
    public Optional<Entity> getSource() {
        return Optional.ofNullable(explosion.getDirectSourceEntity());
    }

    /** The blocks it will destroy. Editable: remove a position to spare it. */
    public List<BlockPos> blockList() {
        return blocks;
    }

    /** The entities it will damage and push. Editable: remove one to spare it. */
    public List<Entity> getEntities() {
        return entities;
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
     * Where it goes off, then each block it will destroy and each entity it will hurt, as the lists stand now: a
     * listener that spares a block or an entity takes its place out too. Not {@link #getArea()}, the farthest it could
     * reach, which stays the same however much is spared.
     */
    @Override
    public Places getPlaces() {
        List<Place> hit = new ArrayList<>();
        for (BlockPos pos : blockList()) hit.add(Place.Spot.at(getLevel(), pos));
        for (Entity entity : getEntities()) hit.add(Place.of(entity));
        return Places.of(Place.Spot.at(getLevel(), getLocation()), hit);
    }
}
