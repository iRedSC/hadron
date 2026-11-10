package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A boat or minecart is being hit: a punch, an arrow, a blast, lava. Enough hits break it, and a creative player's
 * one hit does. Bukkit's {@code VehicleDamageEvent}. Cancel it and the hit does nothing: no wobble, no damage, nothing
 * breaks or drops, and a TNT minecart hit by a burning arrow doesn't explode. Living mounts (horses, pigs) are
 * {@link EntityDamageEvent}.
 *
 * <p>Every hit fires it, whatever its amount. An amount Minecraft was handed that isn't a finite number of at least 0
 * (only a mod's mistake makes one) is reported as one, and that is what the hit then deals: a negative or NaN amount
 * as 0, an infinite one as {@code Float.MAX_VALUE}.
 */
@FiredBy(core = FULL)
public class VehicleDamageEvent extends EntityEvent implements Cancellable {
    private final DamageSource source;
    private final float damage;
    private boolean cancelled;

    /**
     * @param damage how hard it was hit: finite and at least 0. Report an infinite hit as {@code Float.MAX_VALUE} and a
     *     negative or NaN one as 0, and apply what was reported.
     */
    public VehicleDamageEvent(VehicleEntity vehicle, DamageSource source, float damage, Actor actor) {
        super(vehicle, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = DamageAmounts.checked(damage);
    }

    @Override
    public VehicleEntity getEntity() {
        return (VehicleEntity) super.getEntity();
    }

    /** Minecraft's description of the hit. */
    public DamageSource getSource() {
        return source;
    }

    /** How hard it was hit, before Minecraft scales it into the vehicle's wear. */
    public float getDamage() {
        return damage;
    }

    /** The entity that hit it directly: the arrow, or the player who punched. Empty for lava, cactus and the like. */
    public Optional<Entity> getDamager() {
        return Optional.ofNullable(source.getDirectEntity());
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
