package dev.iredsc.hadron.neoforge;

import com.google.common.collect.ImmutableList;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.neoforge.mixin.entity.EntityPassengersAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Where a mount seats its rider, decided before {@code EntityMountEvent} fires, so the event's {@code getTo()} is where
 * the rider ends up. Server thread only.
 *
 * <p>Vanilla seats a rider with {@code positionRider}: the vehicle's {@code getPassengerRidingPosition} for it, less
 * the rider's {@code getVehicleAttachmentPoint}. Both are getters, but the first reads the rider's place among the
 * vehicle's passengers (a boat's second rider sits at the back and moves the first forward) and the second the
 * rider's size standing, which {@code startRiding} makes it before seating. So the question is asked with the
 * vehicle's passenger list swapped for the one it will have and the rider standing, and both are put back before the
 * event fires: no game event, no packet, nothing told to either entity.
 *
 * <p>Vanilla seats only a player at once, and anything else on the vehicle's next tick, by when the vehicle may have
 * moved. So when the mount goes ahead and someone listens, the rider and everything riding it are seated right then
 * ({@link #seated}), with vanilla's own {@code positionRider}.
 */
public final class EntityMounts {
    /** Where the rider will sit, and everything riding it with where each ends up. */
    record Seating(Vec3 to, Carried carried) {}

    private EntityMounts() {}

    /** Where {@code vehicle} will seat {@code rider}, and each entity the rider carries. Changes nothing. */
    static Seating seating(Entity rider, Entity vehicle) {
        EntityPassengersAccessor seats = (EntityPassengersAccessor) vehicle;
        ImmutableList<Entity> were = seats.hadron$passengers();
        Pose pose = rider.getPose();
        Vec3 at = rider.position();
        try {
            seats.hadron$setPassengers(with(were, rider));
            if (pose != Pose.STANDING) rider.setPose(Pose.STANDING);
            Vec3 to = vehicle.getPassengerRidingPosition(rider).subtract(rider.getVehicleAttachmentPoint(vehicle));
            List<Carried.Ride> rides = new ArrayList<>();
            carried(rider, to, to, 0, rides);
            return new Seating(to, Carried.of(rides));
        } finally {
            seats.hadron$setPassengers(were);
            if (rider.getPose() != pose) rider.setPose(pose);
            // Standing up can nudge a mob out of a tight space; it hasn't stood up.
            if (!rider.position().equals(at)) rider.setPos(at);
        }
    }

    /** {@code were} with {@code rider} added as {@code Entity.addPassenger} adds it: a player ahead of a mob driver. */
    private static ImmutableList<Entity> with(ImmutableList<Entity> were, Entity rider) {
        if (were.contains(rider)) return were;
        List<Entity> list = new ArrayList<>(were);
        if (!were.isEmpty() && rider instanceof Player && !(were.get(0) instanceof Player)) {
            list.add(0, rider);
        } else {
            list.add(rider);
        }
        return ImmutableList.copyOf(list);
    }

    /**
     * Adds a ride for each entity on {@code carrier}, itself seated at {@code at}: its seat there, exact while every
     * seat from the rider up sits straight above its carrier. A seat set off to the side turns with its carrier, and
     * a mount can turn the rider as it seats it (a boat faces it forward), so such a ride ends in the area within
     * {@code swing} blocks of the rider's own seat, {@code root}, at the seat's height.
     */
    private static void carried(Entity carrier, Vec3 at, Vec3 root, double swing, List<Carried.Ride> out) {
        ServerLevel level = (ServerLevel) carrier.level();
        for (Entity passenger : carrier.getPassengers()) {
            Vec3 seat = carrier.getPassengerRidingPosition(passenger).subtract(carrier.position());
            Vec3 attach = passenger.getVehicleAttachmentPoint(carrier);
            Vec3 to = at.add(seat).subtract(attach);
            double reach = swing + Math.hypot(seat.x, seat.z) + Math.hypot(attach.x, attach.z);
            Place.Spot end = reach < 1.0E-9 ? Place.Spot.at(level, to) : new Place.Spot(level, BoundingBox.fromCorners(
                    BlockPos.containing(root.x - reach, to.y, root.z - reach),
                    BlockPos.containing(root.x + reach, to.y, root.z + reach)));
            out.add(new Carried.Ride(passenger, Place.of(passenger), end));
            carried(passenger, to, root, reach, out);
        }
    }

    /**
     * {@code Entity.startRiding} mounted {@code rider} on {@code vehicle}: seats it now, and everything riding it,
     * where its event said. Nothing when nobody listens: vanilla seats them on the vehicle's next tick.
     */
    public static void seated(Entity rider, Entity vehicle) {
        if (rider.level().isClientSide || !Hooks.listens(EntityMountEvent.class)) return;
        vehicle.positionRider(rider);
        seatRiders(rider);
    }

    private static void seatRiders(Entity carrier) {
        for (Entity passenger : carrier.getPassengers()) {
            carrier.positionRider(passenger);
            seatRiders(passenger);
        }
    }
}
