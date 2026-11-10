package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.VehicleDamageEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.VehicleEntity;

/** Hits on entities that aren't alive, so NeoForge's damage event never sees them: vehicles and end crystals. */
public final class VehicleHooks {
    private VehicleHooks() {}

    /** True if a plugin refused this hit on a boat or minecart. */
    public static boolean refuseHit(VehicleEntity vehicle, DamageSource source, float amount) {
        if (!(vehicle.level() instanceof ServerLevel) || vehicle.isRemoved() || vehicle.isInvulnerableTo(source)
                || !Hooks.listens(VehicleDamageEvent.class)) return false;
        return Hooks.post(new VehicleDamageEvent(vehicle, source, amount, EntityActors.damage(source, vehicle)))
                .isCancelled();
    }

    /** True if a plugin refused the removal a hit is about to cause (an end crystal shattering). */
    public static boolean refuseShatter(Entity entity, DamageSource source) {
        if (!(entity.level() instanceof ServerLevel) || !Hooks.listens(EntityRemoveEvent.class)) return false;
        return Hooks.post(new EntityRemoveEvent(entity, EntityRemoveEvent.Cause.KILLED,
                EntityActors.damage(source, entity))).isCancelled();
    }
}
