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
    /** The moving piston block a piston tick is moving entities for, so each names it; null outside one. */
    @StaticState(reason = "set only while a piston block entity ticks on the server thread, cleared in finally")
    private static @Nullable BlockPos pushing;

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

    // ---- Pistons ------------------------------------------------------------------------------------------------

    /** Runs a piston block's entity moves for this tick, each naming the moving block at {@code pos}. */
    public static void pushing(BlockPos pos, Runnable moves) {
        BlockPos was = pushing;
        pushing = pos.immutable();
        try {
            moves.run();
        } finally {
            pushing = was;
        }
    }

    /** One entity and where it stood, and how it faced, before a piston moved it or what it rides. */
    private record Stood(Entity entity, double x, double y, double z, float yRot, float xRot) {
        static Stood of(Entity e) {
            return new Stood(e, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot());
        }

        boolean crossed() {
            return !sameBlock(entity.getX(), entity.getY(), entity.getZ(), x, y, z);
        }

        void putBack() {
            if (entity instanceof ServerPlayer player) {
                player.connection.teleport(x, y, z, yRot, xRot);
            } else {
                entity.setPos(x, y, z);
                entity.setYRot(yRot);
                entity.setXRot(xRot);
            }
        }
    }

    /**
     * A piston moving {@code entity} (vanilla's {@code moveEntityByPiston}, on each tick of its travel): moved, its
     * passengers seated as they will be, and, if it or one of them is in another block, the move is asked about,
     * naming the piston as its actor. Refused, it and its passengers are put back; the piston's blocks finish moving.
     */
    public static void pushed(Entity entity, Runnable move) {
        BlockPos piston = pushing;
        if (piston == null || !(entity.level() instanceof ServerLevel level) || !Hooks.listens(type(entity))) {
            move.run();
            return;
        }
        List<Stood> stood = new ArrayList<>();
        stood.add(Stood.of(entity));
        for (Entity r : entity.getIndirectPassengers()) stood.add(Stood.of(r));
        move.run();
        seat(entity);
        if (stood.stream().noneMatch(Stood::crossed)) return;
        Map<Entity, Vec3> starts = new IdentityHashMap<>();
        for (Stood s : stood.subList(1, stood.size())) starts.put(s.entity(), new Vec3(s.x(), s.y(), s.z()));
        Stood root = stood.get(0);
        Actor actor = Actors.currentOr(new Actor.Machine(level, piston, Attribution.none()));
        if (refused(entity, new Vec3(root.x(), root.y(), root.z()), starts, actor)) {
            stood.forEach(Stood::putBack);
            return;
        }
        // Its own check starts from where the piston left it, so the push isn't seen twice.
        EntityState state = (EntityState) entity;
        if (state.hadron$tracked()) trackRoot(entity);
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
