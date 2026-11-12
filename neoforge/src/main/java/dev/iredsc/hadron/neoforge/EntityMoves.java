package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.Event;
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
import java.util.List;

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
        if (player ? entity.isPassenger() : !(driver instanceof Player)) {
            if (state.hadron$tracked()) {
                state.hadron$untrack();
                release(entity);
            }
            return;
        }
        if (!state.hadron$tracked() || state.hadron$trackedLevel() != entity.level()) {
            release(entity);
            track(entity);
            return;
        }
        boolean crossed = !sameBlock(entity.getX(), entity.getY(), entity.getZ(), state.hadron$trackedX(),
                state.hadron$trackedY(), state.hadron$trackedZ());
        if (!crossed && !entity.isVehicle()) {
            track(entity);
            return;
        }
        Class<? extends Event> type = player ? PlayerMoveEvent.class : EntityMoveEvent.class;
        if (!Hooks.listens(type)) {
            release(entity);
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

    /** Fires the move of {@code entity} from {@code from} to where it is now; whether a listener refused it. */
    private static boolean refused(Entity entity, @Nullable LivingEntity driver, Vec3 from) {
        Vec3 to = entity.position();
        EntityMoveEvent event = entity instanceof ServerPlayer player
                ? new PlayerMoveEvent(player, from, to)
                : new EntityMoveEvent(entity, from, to, EntityActors.of(java.util.Objects.requireNonNull(driver,
                        "a checked vehicle has a player driving it")));
        return Hooks.post(event).isCancelled();
    }

    /**
     * Called after {@code vehicle} seated {@code rider} (its own tick does, after the vehicle ticked). A seat that moved
     * during the vehicle's tick (a rearing horse tips its rider back) can be in another block than the last check saw,
     * after that check let the move through: the vehicle's move is asked about again, and a refusal holds the rider
     * where it was until the next check.
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
            seat.hadron$seat(root, rider.getX(), rider.getY(), rider.getZ());
            return;
        }
        rider.setPos(seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ());
        seat.hadron$holdBy(vehicle.getId());
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
            ((EntityState) rider).hadron$holdBy(0); // a hold lasts until this check
            vehicle.positionRider(rider);
            crossed |= leftSeat(root, rider);
            crossed |= seatRiders(root, rider);
        }
        return crossed;
    }

    /** Whether the last check saw {@code rider} on {@code vehicle}, and in another block than now. */
    private static boolean leftSeat(Entity vehicle, Entity rider) {
        EntityState seat = (EntityState) rider;
        return seat.hadron$seatOf() == vehicle.getId() && !sameBlock(rider.getX(), rider.getY(), rider.getZ(),
                seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ());
    }

    /** Lets every rider a refused move of {@code vehicle} held be seated again. */
    private static void release(Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity rider = riders.get(i);
            ((EntityState) rider).hadron$holdBy(0);
            release(rider);
        }
    }

    /** Remembers where the entity is, how it faces and its pose, and where each of its riders is. */
    private static void track(Entity entity) {
        EntityState state = (EntityState) entity;
        state.hadron$track(entity.level(), entity.getX(), entity.getY(), entity.getZ());
        state.hadron$trackTurn(entity.getYRot(), entity.getXRot(), entity.getPose(),
                entity instanceof Camel camel ? camel.getEntityData().get(Camel.LAST_POSE_CHANGE_TICK) : 0L,
                entity.level().getGameTime());
        trackRiders(entity.getId(), entity);
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
            if (leftSeat(entity, rider)) {
                EntityState seat = (EntityState) rider;
                rider.setPos(seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ());
                Entity on = rider.getVehicle();
                if (on != null) seat.hadron$holdBy(on.getId()); // what seats it, which is what must leave it be
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
