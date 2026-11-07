package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A projectile is fired or thrown: an arrow, a trident, a snowball, a fireball, a modded shell. The actor is the
 * shooter. Cancel it and the projectile never appears, but the ammunition or thrown item is already spent; to stop a
 * weapon before it spends anything, cancel the item use instead.
 */
@FiredBy(core = FULL)
public class ProjectileLaunchEvent extends EntitySpawnEvent {
    public ProjectileLaunchEvent(Projectile projectile, Actor actor) {
        super(projectile, null, actor);
    }

    @Override
    public Projectile getEntity() {
        return (Projectile) super.getEntity();
    }

    /** Who fired it, if it has an owner that is loaded. */
    public Optional<Entity> getShooter() {
        return Optional.ofNullable(getEntity().getOwner());
    }
}
