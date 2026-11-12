package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import dev.iredsc.hadron.checks.StaticState;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The block-crossing check behind {@link EntityMoveEvent} and {@link PlayerMoveEvent}: observe, then ask, then undo.
 * Nothing is predicted. Checked are players riding nothing and vehicles a player drives (a root entity); a piston
 * moving any entity is checked where it moves it ({@link #pushed}).
 *
 * <p>A root's own position, facing and pose are remembered at the end of each of its ticks (a few field writes, with
 * nobody listening too): moves that arrive between ticks (a client steering its boat, a player walking) are measured
 * from there. When someone listens, each rider's position and facing is snapshotted at the start of the root's tick
 * ({@code ServerLevel.tickNonPassenger}); at its end, once vanilla has moved the root and seated every passenger, the
 * root's move is fired once if it or any rider is in another block, each rider's observed start and end as a ride.
 * Refused, the whole snapshot is put back, the root and every rider, before any client is sent the move.
 */
public final class EntityMoves {
    private EntityMoves() {}

    /** Whether the move check watches {@code entity} itself: a player riding nothing, or a vehicle a player drives. */
    private static boolean checked(Entity entity) {
        if (entity instanceof ServerPlayer) return !entity.isPassenger();
        return !entity.isPassenger() && entity.getControllingPassenger() instanceof Player;
    }

    private static Class<? extends EntityMoveEvent> type(Entity entity) {
        return entity instanceof ServerPlayer ? PlayerMoveEvent.class : EntityMoveEvent.class;
    }

    /** At the start of a root entity's tick: a baseline the first time, and, when someone listens, its riders. */
    public static void beforeTick(Entity entity) {
        EntityState state = (EntityState) entity;
        if (!checked(entity)) {
            // A rider is checked with its root; one that was a root (a player who got on) starts afresh when it gets off.
            if (state.hadron$tracked()) state.hadron$untrack();
            return;
        }
        if (!state.hadron$tracked() || state.hadron$trackedLevel() != entity.level()) trackRoot(entity);
        if (!entity.isVehicle() || !Hooks.listens(type(entity))) return;
        long now = entity.level().getGameTime();
        snapshotRiders(entity.getId(), now, entity);
        state.hadron$setCheckAt(now);
    }

    /**
     * At the end of a root entity's tick, once vanilla has moved it and seated every passenger: if it or a rider is in
     * another block, its move is asked about, and refused, all of it is put back.
     */
    public static void afterTick(Entity entity) {
        EntityState state = (EntityState) entity;
        if (!state.hadron$tracked()) return;
        if (entity.isRemoved() || !checked(entity) || state.hadron$trackedLevel() != entity.level()) {
            state.hadron$untrack();
            return;
        }
        if (!Hooks.listens(type(entity))) {
            trackRoot(entity);
            return;
        }
        long now = entity.level().getGameTime();
        boolean riders = state.hadron$checkAt() == now;
        boolean crossed = !sameBlock(entity.getX(), entity.getY(), entity.getZ(), state.hadron$trackedX(),
                state.hadron$trackedY(), state.hadron$trackedZ());
        if (!crossed && riders) crossed = riderCrossed(entity.getId(), now, entity);
        if (!crossed) {
            trackRoot(entity);
            return;
        }
        Vec3 from = new Vec3(state.hadron$trackedX(), state.hadron$trackedY(), state.hadron$trackedZ());
        Map<Entity, Vec3> starts = riders ? starts(entity.getId(), now, entity) : Map.of();
        LivingEntity driver = entity instanceof ServerPlayer ? null : entity.getControllingPassenger();
        Actor actor = entity instanceof ServerPlayer player ? new Actor.Player(player)
                : EntityActors.of(java.util.Objects.requireNonNull(driver, "a checked vehicle has a player driving it"));
        if (!refused(entity, from, starts, actor)) {
            trackRoot(entity);
            return;
        }
        putBack(entity, from, riders ? now : -1);
        if (driver instanceof ServerPlayer player) player.connection.send(new ClientboundMoveVehiclePacket(entity));
        trackRoot(entity);
    }

    /** Fires the move of {@code entity} from {@code from} to where it is now; whether a listener refused it. */
    private static boolean refused(Entity entity, Vec3 from, Map<Entity, Vec3> starts, Actor actor) {
        Vec3 to = entity.position();
        EntityMoveEvent event = entity instanceof ServerPlayer player
                ? new PlayerMoveEvent(player, from, to, starts, actor)
                : new EntityMoveEvent(entity, from, to, starts, actor);
        return Hooks.post(event).isCancelled();
    }

    private static boolean sameBlock(double x, double y, double z, double x2, double y2, double z2) {
        return Mth.floor(x) == Mth.floor(x2) && Mth.floor(y) == Mth.floor(y2) && Mth.floor(z) == Mth.floor(z2);
    }

    /** Snapshots where every rider of {@code vehicle} is and how it faces, riders of riders too, for root {@code id}. */
    private static void snapshotRiders(int id, long now, Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity r = riders.get(i);
            ((EntityState) r).hadron$seat(id, now, r.getX(), r.getY(), r.getZ(), r.getYRot(), r.getXRot());
            snapshotRiders(id, now, r);
        }
    }

    /** Whether a rider snapshotted for root {@code id} this tick is in another block than then. */
    private static boolean riderCrossed(int id, long now, Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity r = riders.get(i);
            EntityState seat = (EntityState) r;
            if (seat.hadron$seatOf() == id && seat.hadron$seatAt() == now && !sameBlock(r.getX(), r.getY(), r.getZ(),
                    seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ())) {
                return true;
            }
            if (riderCrossed(id, now, r)) return true;
        }
        return false;
    }

    /** Where each rider snapshotted this tick set out from; a rider that got on since sets out from where it is. */
    private static Map<Entity, Vec3> starts(int id, long now, Entity root) {
        Map<Entity, Vec3> out = new IdentityHashMap<>();
        for (Entity r : root.getIndirectPassengers()) {
            EntityState seat = (EntityState) r;
            if (seat.hadron$seatOf() == id && seat.hadron$seatAt() == now) {
                out.put(r, new Vec3(seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ()));
            }
        }
        return out;
    }

    /**
     * Puts a refused move back: the root where it was, facing and posed as it was, and, when its riders were
     * snapshotted at {@code now}, each exactly where and how it was at the start of the tick.
     */
    private static void putBack(Entity entity, Vec3 from, long now) {
        if (entity instanceof ServerPlayer player) {
            player.connection.teleport(from.x, from.y, from.z, player.getYRot(), player.getXRot());
        } else {
            turnBack(entity);
            EntityTeleports.quietly(entity, () -> entity.teleportTo(from.x, from.y, from.z));
            entity.setDeltaMovement(Vec3.ZERO);
        }
        if (now < 0) return;
        for (Entity r : entity.getIndirectPassengers()) {
            EntityState seat = (EntityState) r;
            if (seat.hadron$seatOf() != entity.getId() || seat.hadron$seatAt() != now) continue;
            r.setPos(seat.hadron$seatX(), seat.hadron$seatY(), seat.hadron$seatZ());
            r.setYRot(seat.hadron$seatYRot());
            r.setXRot(seat.hadron$seatXRot());
        }
    }

    /** Remembers where a root entity is, how it faces and its pose: what a move is measured from and put back to. */
    private static void trackRoot(Entity entity) {
        EntityState state = (EntityState) entity;
        state.hadron$track(entity.level(), entity.getX(), entity.getY(), entity.getZ());
        state.hadron$trackTurn(entity.getYRot(), entity.getXRot(), entity.getPose(),
                entity instanceof Camel camel ? camel.getEntityData().get(Camel.LAST_POSE_CHANGE_TICK) : 0L,
                entity.level().getGameTime());
    }

    /** The vehicle's facing and pose as remembered; a camel's sitting or standing clock too. */
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
            // size; negative while sitting): set the clock so that is as long as it was when remembered.
            long was = state.hadron$trackedPoseTick();
            long poseTime = state.hadron$trackedAt() - Math.abs(was);
            long clock = camel.level().getGameTime() - poseTime;
            camel.resetLastPoseChangeTick(was < 0 ? -clock : clock);
        }
    }

    /** After a teleport, start from where the entity landed rather than seeing the jump as a move. */
    static void retrack(Entity entity) {
        EntityState state = (EntityState) entity;
        if (!state.hadron$tracked()) return;
        trackRoot(entity);
        long now = entity.level().getGameTime();
        if (state.hadron$checkAt() == now) snapshotRiders(entity.getId(), now, entity);
    }

    // ---- Packets ----------------------------------------------------------------------------------------------

    /** One entity as it stood, and how it faced: to measure a move from and put it back. */
    private record Stood(Entity entity, double x, double y, double z, float yRot, float xRot) {
        static Stood of(Entity e) {
            return new Stood(e, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot());
        }

        Vec3 position() {
            return new Vec3(x, y, z);
        }

        boolean crossed() {
            return !sameBlock(entity.getX(), entity.getY(), entity.getZ(), x, y, z);
        }

        /** Back where it stood, how it faced; a rider simply placed, since its vehicle seats it. */
        void putBack() {
            entity.setPos(x, y, z);
            entity.setYRot(yRot);
            entity.setXRot(xRot);
        }
    }

    /** Everyone riding {@code root}, riders of riders too, as they stand. */
    private static List<Stood> riders(Entity root) {
        List<Stood> out = new ArrayList<>();
        for (Entity r : root.getIndirectPassengers()) out.add(Stood.of(r));
        return out;
    }

    private static Map<Entity, Vec3> positions(List<Stood> stood) {
        Map<Entity, Vec3> out = new IdentityHashMap<>();
        for (Stood s : stood) out.put(s.entity(), s.position());
        return out;
    }

    /** Whether a walking player's movement packet is checked: they ride nothing and someone listens. */
    public static boolean checksWalk(ServerPlayer player) {
        return !player.isPassenger() && Hooks.listens(PlayerMoveEvent.class);
    }

    /** Whether a driver's vehicle packet is checked: they drive what they ride and someone listens. */
    public static boolean checksSteer(ServerPlayer driver) {
        Entity root = driver.getRootVehicle();
        return root != driver && root.getControllingPassenger() == driver && Hooks.listens(EntityMoveEvent.class);
    }

    /**
     * A walking player's movement packet ({@code handleMovePlayer}): applied, then, if they or anything riding them is
     * in another block, asked about there, before anything is broadcast. Refused, they are put back and their client
     * told (a teleport, as vanilla corrects a bad move).
     */
    public static void walked(ServerPlayer player, Runnable handle) {
        if (player.isPassenger() || !Hooks.listens(PlayerMoveEvent.class)) {
            handle.run();
            return;
        }
        Stood was = Stood.of(player);
        List<Stood> riders = riders(player);
        handle.run();
        if (player.isRemoved() || player.isPassenger()) return;
        seat(player);
        if (!was.crossed() && riders.stream().noneMatch(Stood::crossed)) return;
        if (refused(player, was.position(), positions(riders), new Actor.Player(player))) {
            player.connection.teleport(was.x(), was.y(), was.z(), player.getYRot(), player.getXRot());
            riders.forEach(Stood::putBack);
        }
        if (((EntityState) player).hadron$tracked()) trackRoot(player);
    }

    /**
     * A driver's vehicle packet ({@code handleMoveVehicle}): vanilla applies it and seats only the driver; here every
     * rider is seated too, and, if the vehicle or any rider is in another block, the move is asked about there, before
     * anything is broadcast. Refused, the vehicle and every rider are put back, the handler's own record of where the
     * vehicle last was with them, and the driver's client is sent where the vehicle really is.
     */
    public static void steered(ServerPlayer driver, Runnable handle, VehiclePackets packets) {
        Entity root = driver.getRootVehicle();
        if (root == driver || root.getControllingPassenger() != driver || !Hooks.listens(EntityMoveEvent.class)) {
            handle.run();
            return;
        }
        Stood was = Stood.of(root);
        List<Stood> riders = riders(root);
        handle.run();
        if (root.isRemoved() || driver.getRootVehicle() != root) return;
        seat(root);
        if (!was.crossed() && riders.stream().noneMatch(Stood::crossed)) return;
        if (refused(root, was.position(), positions(riders), EntityActors.of(driver))) {
            root.absMoveTo(was.x(), was.y(), was.z(), was.yRot(), was.xRot());
            riders.forEach(Stood::putBack);
            packets.lastGood(was.x(), was.y(), was.z());
            driver.connection.send(new ClientboundMoveVehiclePacket(root));
        }
        if (((EntityState) root).hadron$tracked()) trackRoot(root);
    }

    /** Where the packet handler last saw its player's vehicle in a good place, which a refusal puts back too. */
    @FunctionalInterface
    public interface VehiclePackets {
        void lastGood(double x, double y, double z);
    }

    // ---- Pistons ------------------------------------------------------------------------------------------------

    /**
     * One piston block's entity moves for one tick: the moving block they name, and a slime launch vanilla set for an
     * entity just before pushing it, held until that push is asked about. One per call, kept per thread, so the client
     * and server sides of a singleplayer game, and a push nested in another, never share one.
     */
    private static final class Push {
        final BlockPos pos;
        @Nullable Entity launched;
        @Nullable Runnable launch;

        Push(BlockPos pos) {
            this.pos = pos.immutable();
        }

        void launchHeld() {
            Runnable held = launch;
            launched = null;
            launch = null;
            if (held != null) held.run();
        }
    }

    @StaticState(reason = "the server thread's piston tick in progress, set and restored around it; per thread")
    private static final ThreadLocal<@Nullable Push> PUSH = new ThreadLocal<>();

    /** Runs a server piston block's entity moves for this tick, each naming the moving block at {@code pos}. */
    public static void pushing(BlockPos pos, Runnable moves) {
        Push was = PUSH.get();
        Push now = new Push(pos);
        PUSH.set(now);
        try {
            moves.run();
        } finally {
            now.launchHeld();
            PUSH.set(was);
        }
    }

    /**
     * A slime block launching an entity it is about to push: held until that push is asked about, so a refused push
     * launches nothing; any held for an entity that then isn't pushed is applied as vanilla would.
     */
    public static void launching(Entity entity, Runnable set) {
        Push push = PUSH.get();
        if (push == null || !(entity.level() instanceof ServerLevel) || !Hooks.listens(type(entity))) {
            set.run();
            return;
        }
        push.launchHeld();
        push.launched = entity;
        push.launch = set;
    }

    /**
     * Where vanilla's {@code move(PISTON, delta)} would take {@code entity}, without moving it: the per-tick piston
     * limit, a cobweb's slowing, then collision with step-up, as {@code move} applies them.
     */
    public static Vec3 resolve(Entity entity, Vec3 delta, java.util.function.Function<Vec3, Vec3> collide,
            double[] pistonDeltas, long pistonDeltasGameTime, Vec3 stuck) {
        if (entity.noPhysics) return delta;
        if (delta.lengthSqr() <= 1.0E-7) return delta;
        boolean fresh = entity.level().getGameTime() != pistonDeltasGameTime;
        Vec3 limited = Vec3.ZERO;
        for (Direction.Axis axis : Direction.Axis.values()) {
            double d = delta.get(axis);
            if (d == 0) continue;
            double before = fresh ? 0 : pistonDeltas[axis.ordinal()];
            double moved = Mth.clamp(d + before, -0.51, 0.51) - before;
            if (Math.abs(moved) <= 1.0E-5F) return Vec3.ZERO;
            limited = limited.with(axis, moved);
            break; // vanilla moves along the first axis set
        }
        if (stuck.lengthSqr() > 1.0E-7) limited = limited.multiply(stuck);
        return collide.apply(limited);
    }

    /**
     * A piston about to move {@code entity} by {@code resolved}, where vanilla's own collision and step-up take it
     * (vanilla's {@code moveEntityByPiston}, on each tick of its travel): asked about before it moves, if it or a
     * passenger would be in another block, naming the piston as its actor. Refused, nothing moves (nothing is stepped
     * on, nothing launched) and it is pinned where it stands; the piston's blocks finish moving. Let through, vanilla
     * moves it and its passengers are seated.
     */
    public static void pushed(Entity entity, Vec3 resolved, Runnable move) {
        Push push = PUSH.get();
        if (push == null || !(entity.level() instanceof ServerLevel level) || !Hooks.listens(type(entity))) {
            if (push != null && push.launched == entity) push.launchHeld();
            move.run();
            return;
        }
        Stood was = Stood.of(entity);
        List<Stood> riders = riders(entity);
        Vec3 going = was.position().add(resolved);
        boolean crosses = !sameBlock(going.x, going.y, going.z, was.x(), was.y(), was.z())
                || riders.stream().anyMatch(r -> !sameBlock(r.x() + resolved.x, r.y() + resolved.y,
                        r.z() + resolved.z, r.x(), r.y(), r.z()));
        Actor actor = Actors.currentOr(new Actor.Machine(level, push.pos, Attribution.none()));
        if (crosses) {
            Map<Entity, Vec3> to = new IdentityHashMap<>();
            for (Stood r : riders) to.put(r.entity(), r.position().add(resolved));
            EntityMoveEvent event = entity instanceof ServerPlayer player
                    ? new PlayerMoveEvent(player, was.position(), going, positions(riders), to, actor)
                    : new EntityMoveEvent(entity, was.position(), going, positions(riders), to, actor);
            if (Hooks.post(event).isCancelled()) {
                if (push.launched == entity) {
                    push.launched = null;
                    push.launch = null;
                }
                ((EntityState) entity).hadron$pin(was.x(), was.y(), was.z());
                return;
            }
        }
        if (push.launched == entity) push.launchHeld();
        move.run();
        seat(entity);
        // A backstop: the entity's own move can still put it somewhere the resolution didn't (a shulker snaps to a
        // whole block). Where it or a rider landed in another block than the one asked about (or than where it stood,
        // if nothing was asked), that observed outcome is asked about; refused, all of it goes back.
        boolean elsewhere = !sameBlock(entity.getX(), entity.getY(), entity.getZ(), going.x, going.y, going.z)
                || riders.stream().anyMatch(r -> !sameBlock(r.entity().getX(), r.entity().getY(), r.entity().getZ(),
                        r.x() + resolved.x, r.y() + resolved.y, r.z() + resolved.z));
        if (elsewhere && (was.crossed() || riders.stream().anyMatch(Stood::crossed))) {
            EntityMoveEvent landed = entity instanceof ServerPlayer player
                    ? new PlayerMoveEvent(player, was.position(), player.position(), positions(riders), actor)
                    : new EntityMoveEvent(entity, was.position(), entity.position(), positions(riders), actor);
            if (Hooks.post(landed).isCancelled()) {
                if (entity instanceof ServerPlayer player) {
                    player.connection.teleport(was.x(), was.y(), was.z(), was.yRot(), was.xRot());
                } else {
                    was.putBack();
                }
                riders.forEach(Stood::putBack);
                ((EntityState) entity).hadron$pin(was.x(), was.y(), was.z());
                return;
            }
        }
        // Its own check starts from where the piston left it, so the push isn't seen twice.
        if (((EntityState) entity).hadron$tracked()) trackRoot(entity);
    }

    /** Whether anyone listens to moves a piston could make: otherwise a piston's travel tick is left to vanilla. */
    public static boolean watchesPushes() {
        return Hooks.listens(EntityMoveEvent.class) || Hooks.listens(PlayerMoveEvent.class);
    }

    /** Whether a server piston's push of {@code entity} is asked about at all: worth resolving its destination. */
    public static boolean checksPush(Entity entity) {
        return PUSH.get() != null && entity.level() instanceof ServerLevel && Hooks.listens(type(entity));
    }

    /** Seats every passenger of {@code vehicle} where it now puts them, riders of riders after the one they ride. */
    private static void seat(Entity vehicle) {
        List<Entity> riders = vehicle.getPassengers();
        for (int i = 0; i < riders.size(); i++) {
            Entity r = riders.get(i);
            vehicle.positionRider(r);
            seat(r);
        }
    }
}
