package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The block-crossing check behind {@link EntityMoveEvent} and {@link PlayerMoveEvent}, run before each entity's tick.
 * Only players (not riding anything) and vehicles a player drives are checked. It fires when the entity, or anything
 * riding it, is in another block than at the last check: a turning boat swings its back seat into the next block
 * without the boat leaving its own. The last position, facing and pose live in fields on the entity, and each rider's
 * last position in fields on the rider, so nothing is allocated unless something changed block and someone listens.
 * While someone listens, each rider of a checked vehicle is seated before the vehicle ticks, as its own tick is about
 * to, so the event states where the move leaves it.
 */
public final class EntityMoves {
    /** True while the move check itself seats riders, so their seating isn't checked again. */
    @StaticState(reason = "set only while the move check seats riders on the server thread, cleared in finally")
    private static boolean checking;

    private EntityMoves() {}

    /** Called from the server tick, before the entity ticks. */
    public static void beforeTick(Entity entity) {
        EntityState state = (EntityState) entity;
        boolean player = entity instanceof ServerPlayer;
        LivingEntity driver = player ? null : entity.getControllingPassenger();
        boolean checked = player ? !entity.isPassenger() : driver instanceof Player;
        if (!checked) {
            if (state.hadron$tracked()) state.hadron$untrack();
            unwatch(entity);
            return;
        }
        if (!Hooks.listens(player ? PlayerMoveEvent.class : EntityMoveEvent.class)) {
            // Nobody listens: only the entity itself is tracked, a few field writes and no walk over its riders, so a
            // listener that arrives has where it was to start from.
            unwatch(entity);
            trackVehicle(entity);
            return;
        }
        if (!state.hadron$tracked() || state.hadron$trackedLevel() != entity.level()) {
            track(entity);
            return;
        }
        boolean crossed = !sameBlock(entity.getX(), entity.getY(), entity.getZ(), state.hadron$trackedX(),
                state.hadron$trackedY(), state.hadron$trackedZ());
        if (!crossed && !entity.isVehicle()) {
            track(entity);
            return;
        }
        // Where the vehicle as it stands now seats each rider, as its tick is about to: where the move leaves them.
        boolean riderCrossed = seatRiders(entity);
        if (!crossed && !riderCrossed) {
            track(entity);
            return;
        }
        Vec3 from = new Vec3(state.hadron$trackedX(), state.hadron$trackedY(), state.hadron$trackedZ());
        if (!refused(entity, driver, from)) {
            track(entity);
            return;
        }
        putBack(entity, driver, from);
        track(entity);
    }

    /**
     * Fires the move of {@code entity} from {@code from} to where it is now, each rider setting out from where it was
     * before it was seated this tick; whether a listener refused it.
     */
    private static boolean refused(Entity entity, @Nullable LivingEntity driver, Vec3 from) {
        Vec3 to = entity.position();
        Map<Entity, Vec3> starts = new IdentityHashMap<>();
        long now = entity.level().getGameTime();
        for (Entity rider : entity.getIndirectPassengers()) {
            EntityState seat = (EntityState) rider;
            starts.put(rider, seat.hadron$beforeAt() == now
                    ? new Vec3(seat.hadron$beforeX(), seat.hadron$beforeY(), seat.hadron$beforeZ())
                    : rider.position());
        }
        EntityMoveEvent event = entity instanceof ServerPlayer player
                ? new PlayerMoveEvent(player, from, to, starts)
                : new EntityMoveEvent(entity, from, to, starts, EntityActors.of(java.util.Objects.requireNonNull(driver,
                        "a checked vehicle has a player driving it")));
        return Hooks.post(event).isCancelled();
    }

    /**
     * Called after {@code vehicle} seated {@code rider} (its own tick does, after the vehicle ticked). A seat that moved
     * during the vehicle's tick (a rearing horse tips its rider back, a mount lunges) can be in another block than the
     * last check saw, after that check let the move through: the vehicle's move is asked about again, and a refusal
     * puts the whole move back, the vehicle and every rider, as a refusal before the tick does.
     */
    public static void seated(Entity vehicle, Entity rider) {
        if (checking) return;
        EntityState seat = (EntityState) rider;
        int root = seat.hadron$seatOf();
        if (root == 0 || sameBlock(rider.getX(), rider.getY(), rider.getZ(), seat.hadron$seatX(), seat.hadron$seatY(),
                seat.hadron$seatZ())) {
            return;
        }
        Entity top = vehicle.getRootVehicle();
        EntityState state = (EntityState) top;
        if (top.getId() != root || !state.hadron$tracked() || state.hadron$trackedLevel() != top.level()) return;
        boolean player = top instanceof ServerPlayer;
        LivingEntity driver = player ? null : top.getControllingPassenger();
        if (!player && !(driver instanceof Player)) return;
        if (!Hooks.listens(player ? PlayerMoveEvent.class : EntityMoveEvent.class)) return;
        Vec3 from = new Vec3(state.hadron$trackedX(), state.hadron$trackedY(), state.hadron$trackedZ());
        if (!refused(top, driver, from)) {
            // Let through: the vehicle as it now is, and this rider; others not yet seated this tick are asked about
            // when they are.
            trackVehicle(top);
            seat.hadron$seat(root, rider.getX(), rider.getY(), rider.getZ());
            return;
        }
        putBack(top, driver, from);
        track(top);
    }

    private static boolean sameBlock(double x, double y, double z, double x2, double y2, double z2) {
        return Mth.floor(x) == Mth.floor(x2) && Mth.floor(y) == Mth.floor(y2) && Mth.floor(z) == Mth.floor(z2);
    }

    /**
     * Seats every rider of {@code vehicle}, riders of riders after the one they ride, and says whether any the last
     * check saw on it is now in another block than then.
     */
    private static boolean seatRiders(Entity vehicle) {
        checking = true;
        try {
            return seatRiders(vehicle, vehicle);
        } finally {
            checking = false;
        }
    }

    private static boolean seatRiders(Entity root, Entity vehicle) {
        boolean crossed = false;
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity rider = riders.get(i);
            EntityState seat = (EntityState) rider;
            seat.hadron$holdBy(0); // a hold lasts until this check
            seat.hadron$before(rider.getX(), rider.getY(), rider.getZ(), rider.level().getGameTime());
            vehicle.positionRider(rider);
            crossed |= leftSeat(root, rider);
            crossed |= seatRiders(root, rider);
        }
        return crossed;
    }

    /**
     * Where {@code rider} was for the check of {@code vehicle}: where the last check saw it on this vehicle, or, for one
     * no check has seen (riders aren't watched while nobody listens), where it was just before it was seated this tick.
     * Null when neither is known.
     */
    private static @Nullable Vec3 wasAt(Entity vehicle, Entity rider) {
        EntityState seat = (EntityState) rider;
        if (seat.hadron$seatOf() == vehicle.getId()) {
            return new Vec3(seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ());
        }
        if (seat.hadron$beforeAt() == rider.level().getGameTime()) {
            return new Vec3(seat.hadron$beforeX(), seat.hadron$beforeY(), seat.hadron$beforeZ());
        }
        return null;
    }

    /** Whether {@code rider} is in another block than it was for the check of {@code vehicle}. */
    private static boolean leftSeat(Entity vehicle, Entity rider) {
        Vec3 was = wasAt(vehicle, rider);
        return was != null && !sameBlock(rider.getX(), rider.getY(), rider.getZ(), was.x, was.y, was.z);
    }

    /**
     * Lets every rider of {@code vehicle} go: one a refused move held is seated again, and none is watched any more, so
     * seating it costs one field read.
     */
    private static void release(Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity rider = riders.get(i);
            EntityState seat = (EntityState) rider;
            seat.hadron$holdBy(0);
            seat.hadron$seat(0, 0, 0, 0);
            release(rider);
        }
    }

    /** Remembers where the entity is, how it faces and its pose, and where each of its riders is. */
    private static void track(Entity entity) {
        trackVehicle(entity);
        trackRiders(entity.getId(), entity);
        ((EntityState) entity).hadron$setWatched(true);
    }

    /** Stops watching the riders of a vehicle that was watched: the one tick that walks them, to let them go. */
    private static void unwatch(Entity entity) {
        EntityState state = (EntityState) entity;
        if (!state.hadron$watched()) return;
        state.hadron$setWatched(false);
        release(entity);
    }

    /** Remembers where the entity is, how it faces and its pose. */
    private static void trackVehicle(Entity entity) {
        EntityState state = (EntityState) entity;
        state.hadron$track(entity.level(), entity.getX(), entity.getY(), entity.getZ());
        state.hadron$trackTurn(entity.getYRot(), entity.getXRot(), entity.getPose(),
                entity instanceof Camel camel ? camel.getEntityData().get(Camel.LAST_POSE_CHANGE_TICK) : 0L,
                entity.level().getGameTime());
    }

    /** Remembers where each rider of {@code vehicle} is, riders of riders too, as seen by the vehicle {@code root}. */
    private static void trackRiders(int root, Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity rider = riders.get(i);
            ((EntityState) rider).hadron$seat(root, rider.getX(), rider.getY(), rider.getZ());
            trackRiders(root, rider);
        }
    }

    /**
     * Puts a refused move back: the entity where it was, and a vehicle also facing and posed as it was, so each rider's
     * seat is back where it was too.
     */
    private static void putBack(Entity entity, @Nullable LivingEntity driver, Vec3 from) {
        if (entity instanceof ServerPlayer player) {
            player.connection.teleport(from.x, from.y, from.z, player.getYRot(), player.getXRot());
            seatRiders(player);
        } else {
            turnBack(entity);
            // Teleporting seats its riders again, with the facing and pose just put back.
            EntityTeleports.quietly(entity, () -> entity.teleportTo(from.x, from.y, from.z));
            entity.setDeltaMovement(Vec3.ZERO);
            // The driver's client moves the vehicle itself; tell it where the vehicle really is, and its facing.
            if (driver instanceof ServerPlayer player) player.connection.send(new ClientboundMoveVehiclePacket(entity));
        }
        // A seat that moves on its own (a rearing horse, a mod's animated vehicle) may still be in the refused block:
        // its rider is held where it was, through its own tick's seating too, until the next check asks again.
        for (Entity rider : entity.getIndirectPassengers()) {
            Vec3 was = wasAt(entity, rider);
            if (was != null && !sameBlock(rider.getX(), rider.getY(), rider.getZ(), was.x, was.y, was.z)) {
                rider.setPos(was.x, was.y, was.z);
                Entity on = rider.getVehicle();
                // What seats it, which is what must leave it be.
                if (on != null) ((EntityState) rider).hadron$holdBy(on.getId());
            }
        }
    }

    /** The vehicle's facing and pose as the last check saw them; a camel's sitting or standing clock too. */
    private static void turnBack(Entity entity) {
        EntityState state = (EntityState) entity;
        float yRot = state.hadron$trackedYRot();
        entity.setYRot(yRot);
        entity.setXRot(state.hadron$trackedXRot());
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = yRot;
            living.setYHeadRot(yRot);
        }
        Pose pose = state.hadron$trackedPose();
        if (pose != null && entity.getPose() != pose) entity.setPose(pose);
        if (entity instanceof Camel camel) {
            // Its seat height follows how long it has been sitting down or standing up (game time minus the clock's
            // size; negative while sitting): set the clock so that is as long as it was at the last check.
            long was = state.hadron$trackedPoseTick();
            long poseTime = state.hadron$trackedAt() - Math.abs(was);
            long clock = camel.level().getGameTime() - poseTime;
            camel.resetLastPoseChangeTick(was < 0 ? -clock : clock);
        }
    }

    /** After a teleport, start from where the entity landed rather than seeing the jump as a move. */
    static void retrack(Entity entity) {
        EntityState state = (EntityState) entity;
        if (state.hadron$tracked()) track(entity);
    }
}
