package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An explosion is about to happen, before any block or entity is touched. Change its size or whether it starts
 * fires, or cancel it so nothing explodes at all. Which blocks and entities it then hits is {@link ExplosionEvent}.
 */
@FiredBy(core = FULL)
public class ExplosionPrimeEvent extends WorldEvent implements Cancellable {
    private final Explosion explosion;
    private float radius;
    private boolean fire;
    private boolean cancelled;

    public ExplosionPrimeEvent(Actor actor, ServerLevel level, Explosion explosion, float radius, boolean fire) {
        super(actor, level, around(explosion.center(), radius * 2.0));
        this.explosion = explosion;
        this.radius = radius;
        this.fire = fire;
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

    /** The explosion's power; TNT is 4. */
    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        if (!(radius >= 0) || Float.isInfinite(radius)) {
            throw new IllegalArgumentException("Explosion radius must be a finite number of at least 0, got " + radius
                    + "; to stop the explosion use setCancelled(true)");
        }
        this.radius = radius;
    }

    /** Whether it sets fires. */
    public boolean getFire() {
        return fire;
    }

    public void setFire(boolean fire) {
        this.fire = fire;
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
