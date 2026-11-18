package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.entity.Landing;
import dev.iredsc.hadron.checks.StaticState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a dismount sets its rider down, decided before {@code EntityDismountEvent} fires and then applied exactly, so
 * the event's {@code getTo()} is where the rider ends up. Server thread only.
 *
 * <p>A living rider getting off through {@code stopRiding} is placed by {@code LivingEntity.dismountVehicle} once it is
 * off; a player placed with {@code dismountTo} goes exactly there; any other rider taken off ({@code removeVehicle}
 * called directly, a rider that isn't living) stays where it is. The spot is worked out with
 * {@code dismountVehicle}'s own rule, which reads only blocks, the vehicle and the rider, none of which getting off
 * changes; finding it may set the rider's pose (crouching to fit), which is put back until the dismount goes ahead.
 */
public final class EntityDismounts {
    /**
     * A dismount in progress: the rider whose {@code stopRiding} or {@code dismountTo} is running, and the spot a
     * caller placed it at ({@code dismountTo}). Once its event fires, the set-down worked out for it; once that event
     * goes ahead, that it is to be applied.
     */
    private static final class Getting {
        final Entity rider;
        final @Nullable Vec3 placedAt;
        final @Nullable Getting outer;
        @Nullable SetDown found;
        boolean goesAhead;

        Getting(Entity rider, @Nullable Vec3 placedAt, @Nullable Getting outer) {
            this.rider = rider;
            this.placedAt = placedAt;
            this.outer = outer;
        }
    }

    /** A set-down worked out for one rider leaving one vehicle: where, and the pose vanilla's search chose. */
    private record SetDown(Entity vehicle, Vec3 at, @Nullable Pose pose) {}

    @StaticState(reason = "the dismounts in progress on the server thread, innermost first; each removed when it returns")
    private static @Nullable Getting getting;

    private EntityDismounts() {}

    /** Runs a living rider's {@code stopRiding}, which places it once it is off. */
    public static void stopRiding(LivingEntity rider, Runnable stop) {
        within(new Getting(rider, null, getting), stop);
    }

    /** Runs a player's {@code dismountTo}, which takes it off and places it exactly at {@code at}. */
    public static void dismountTo(Entity rider, Vec3 at, Runnable dismount) {
        within(new Getting(rider, at, getting), dismount);
    }

    private static void within(Getting scope, Runnable run) {
        Getting outer = getting;
        getting = scope;
        try {
            run.run();
        } finally {
            getting = outer;
        }
    }

    /** The dismount in progress for {@code rider}, innermost first; null when none is. */
    private static @Nullable Getting of(Entity rider) {
        for (Getting g = getting; g != null; g = g.outer) {
            if (g.rider == rider) return g;
        }
        return null;
    }

    /**
     * Where getting off {@code vehicle} will set {@code rider} down, for its event: the spot {@code dismountTo} was
     * given; {@code dismountVehicle}'s own rule for a living rider in {@code stopRiding} (where it is when removed;
     * the vehicle's chosen spot; otherwise above the higher of the two when the vehicle is gone or in a portal); or
     * where it is for any other rider. The rider's pose is left as it was.
     */
    static Vec3 setDown(Entity rider, Entity vehicle) {
        Getting g = of(rider);
        if (g == null) return rider.position();
        // Placed once off, so where it really ends up is where placing puts it (a shulker at a block's centre).
        if (g.placedAt != null) return Landing.of(rider, g.placedAt);
        g.found = rule(rider, vehicle);
        g.goesAhead = false;
        return Landing.of(rider, g.found.at());
    }

    /** {@code dismountVehicle}'s rule, with the pose its search chose; the rider's own pose is put back. */
    private static SetDown rule(Entity rider, Entity vehicle) {
        if (!(rider instanceof LivingEntity living) || rider.isRemoved()) {
            return new SetDown(vehicle, rider.position(), null);
        }
        if (vehicle.isRemoved() || rider.level().getBlockState(vehicle.blockPosition()).is(BlockTags.PORTALS)) {
            return new SetDown(vehicle, new Vec3(rider.getX(), Math.max(rider.getY(), vehicle.getY()), rider.getZ()),
                    null);
        }
        Pose was = rider.getPose();
        Vec3 at = vehicle.getDismountLocationForPassenger(living);
        Pose chosen = rider.getPose();
        if (chosen != was) rider.setPose(was);
        return new SetDown(vehicle, at, chosen);
    }

    /** The event for {@code rider} getting off {@code vehicle} went ahead: set it down where that event said. */
    static void goesAhead(Entity rider, Entity vehicle) {
        Getting g = of(rider);
        if (g != null && g.found != null && g.found.vehicle() == vehicle) g.goesAhead = true;
    }

    /**
     * {@code LivingEntity.dismountVehicle}: sets the rider down where its event said, in the pose vanilla's search
     * chose; returns false when nothing was decided for this rider and vehicle, so vanilla places it.
     */
    public static boolean setDownAsDecided(LivingEntity rider, Entity vehicle) {
        Getting g = of(rider);
        SetDown d = g == null || !g.goesAhead ? null : g.found;
        if (d == null || d.vehicle() != vehicle) return false;
        if (d.pose() != null) rider.setPose(d.pose());
        rider.dismountTo(d.at().x, d.at().y, d.at().z);
        return true;
    }
}
