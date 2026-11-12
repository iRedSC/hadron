package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.api.event.entity.Landing;
import dev.iredsc.hadron.neoforge.mixin.entity.EntityMotionAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * One {@link EntityTeleportEvent} per teleport, however many of Minecraft's teleport methods it passes through. The
 * outermost teleport method asks; anything nested inside it (a player's cross-dimension teleport calling
 * {@code changeDimension}, passengers following their vehicle) is part of that one teleport. Only the travelling
 * entities are covered: anything else a listener teleports meanwhile is a teleport of its own and is asked about.
 * Server thread only: every entry point checks for a {@link ServerLevel} first.
 */
public final class EntityTeleports {
    /** Entities in a teleport already asked about, or being moved by the port itself, with their passengers. */
    @StaticState(reason = "entities mid-teleport on the server thread, removed when their teleport ends")
    private static final Set<Entity> travelling = Collections.newSetFromMap(new IdentityHashMap<>());
    /** A cause NeoForge told us about for this entity in this tick, and whether it was already asked. */
    @StaticState(reason = "a cause NeoForge reported for an entity's next teleport this tick; checked against the tick and cleared when used")
    private static @Nullable Entity marked;
    @StaticState(reason = "a cause NeoForge reported for an entity's next teleport this tick; checked against the tick and cleared when used")
    private static int markedTick;
    @StaticState(reason = "a cause NeoForge reported for an entity's next teleport this tick; checked against the tick and cleared when used")
    private static @Nullable TeleportCause markedCause;
    @StaticState(reason = "a cause NeoForge reported for an entity's next teleport this tick; checked against the tick and cleared when used")
    private static boolean markedAsked;
    /** Where a listener on NeoForge's path sent it, when that changed the level or facing; null otherwise. */
    @StaticState(reason = "a cause NeoForge reported for an entity's next teleport this tick; checked against the tick and cleared when used")
    private static @Nullable Destination markedOverride;

    /** A same-world teleport just asked about, and where everyone stood, to check where its riders land. */
    @StaticState(reason = "the one same-world teleport under way on the server thread, consumed when it lands")
    private static @Nullable Asked asked;

    /** The entity whose random teleport search is running as a probe: placed, never teleported, nothing sent. */
    @StaticState(reason = "the one random teleport search probing on the server thread; cleared when it returns")
    private static @Nullable Entity probing;

    /**
     * An entity that gets off what it rides only once its teleport goes ahead (an ender pearl's thrower, a chorus fruit
     * eater; vanilla gets them off before asking), and how.
     */
    private record Leaving(Entity entity, Runnable leave) {}

    @StaticState(reason = "a rider whose getting off waits for its teleport on the server thread; cleared when the call that set it returns")
    private static @Nullable Leaving leaving;

    /** One entity as it stood: to put back, and to measure a landing from. */
    private record Stood(Entity entity, double x, double y, double z, float yRot, float xRot) {
        static Stood of(Entity e) {
            return new Stood(e, e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot());
        }

        Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    /**
     * What a same-world teleport's event said once every listener had run (its places, as stated then: they are read
     * live and would follow the riders once they land), and everyone travelling as they stood before it.
     */
    private record Asked(EntityTeleportEvent event, List<Place> places, ServerLevel level, long tick, Stood root,
            List<Stood> riders) {}

    private EntityTeleports() {}

    /**
     * Notes a same-world teleport its event let through: once it lands, each rider's block is checked against the
     * event's places ({@link #landed}).
     */
    static void expect(Entity entity, EntityTeleportEvent event) {
        if (!(entity.level() instanceof ServerLevel level) || event.getToLevel() != level) return;
        List<Stood> riders = new ArrayList<>();
        for (Entity r : entity.getIndirectPassengers()) riders.add(Stood.of(r));
        asked = new Asked(event, List.copyOf(event.getPlaces()), level, level.getGameTime(), Stood.of(entity), riders);
    }

    /**
     * A same-world teleport has landed and vanilla has seated its riders: if it or any rider is in a block the event's
     * places don't hold (a seat the event couldn't foresee, an entity whose own placing moves it), that is asked about
     * once more, as the vehicle's {@code EntityMoveEvent} from where it set out, with every rider's observed start and
     * end. Refused, the whole teleport is put back; returns whether it was.
     */
    private static boolean landed(Entity entity) {
        Asked a = asked;
        if (a == null || a.root().entity() != entity) return false;
        asked = null;
        if (entity.level() != a.level() || a.level().getGameTime() != a.tick()) return false;
        boolean outside = outside(entity, a);
        for (Stood r : a.riders()) {
            if (outside) break;
            outside = outside(r.entity(), a);
        }
        if (!outside || !Hooks.listens(EntityMoveEvent.class)) return false;
        Map<Entity, Vec3> starts = new IdentityHashMap<>();
        for (Stood r : a.riders()) starts.put(r.entity(), r.position());
        EntityMoveEvent check = entity instanceof ServerPlayer player
                ? new PlayerMoveEvent(player, a.root().position(), entity.position(), starts, a.event().getActor())
                : new EntityMoveEvent(entity, a.root().position(), entity.position(), starts, a.event().getActor());
        if (!Hooks.post(check).isCancelled()) return false;
        Stood root = a.root();
        quietly(entity, () -> {
            if (entity instanceof ServerPlayer player) {
                player.connection.teleport(root.x(), root.y(), root.z(), root.yRot(), root.xRot());
            } else {
                entity.setYRot(root.yRot());
                entity.setXRot(root.xRot());
                entity.teleportTo(root.x(), root.y(), root.z());
            }
        });
        for (Stood r : a.riders()) {
            r.entity().setPos(r.x(), r.y(), r.z());
            r.entity().setYRot(r.yRot());
            r.entity().setXRot(r.xRot());
        }
        return true;
    }

    /** Whether {@code e} is in the teleport's world, in a block none of its places as stated hold. */
    private static boolean outside(Entity e, Asked a) {
        if (e.level() != a.level()) return false;
        BlockPos at = e.blockPosition();
        return a.places().stream().noneMatch(p -> p instanceof Place.Spot s && s.level() == a.level()
                && s.box().isInside(at));
    }

    /** Whether either is in a teleport under way, so a dismount it causes can't be refused. */
    static boolean inProgress(Entity rider, Entity vehicle) {
        return travelling.contains(rider) || travelling.contains(vehicle);
    }

    private static boolean suppressed(Entity entity) {
        return travelling.contains(entity);
    }

    /**
     * NeoForge named the cause of this entity's coming teleport; {@code asked} if we already fired the event, and
     * {@code override} the level and facing a listener chose there (NeoForge's event carries only the position).
     */
    static void mark(Entity entity, TeleportCause cause, boolean asked, @Nullable Destination override) {
        marked = entity;
        markedTick = entity.tickCount;
        markedCause = cause;
        markedAsked = asked;
        markedOverride = override;
    }

    /** Where a teleport goes. {@code level} null means the entity's own level, on the client. */
    public record Destination(@Nullable ServerLevel level, double x, double y, double z, float yRot, float xRot) {
        /** Whether this differs from the teleport the method was called with. */
        public boolean differs(Entity entity, @Nullable ServerLevel toLevel, double x, double y, double z, float yRot,
                float xRot) {
            ServerLevel asked = toLevel != null ? toLevel
                    : entity.level() instanceof ServerLevel own ? own : null;
            return level != asked || this.x != x || this.y != y || this.z != z || this.yRot != yRot
                    || this.xRot != xRot;
        }

        /** Whether this moves the entity to another level than the one it is in. */
        public boolean crossesLevel(Entity entity) {
            return level != null && level != entity.level();
        }

        public Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    /**
     * Fires the event for a teleport about to start, unless it is nested in one or already asked. Returns where to
     * go, which a listener may have changed, or null when cancelled. {@code toLevel} null means the entity's own
     * level.
     */
    public static @Nullable Destination ask(Entity entity, @Nullable ServerLevel toLevel, double x, double y, double z, float yRot,
            float xRot) {
        return ask(entity, toLevel, x, y, z, yRot, xRot, EntityTeleportEvent.Riders.GO_ALONG);
    }

    /** As above, for a teleport method that does {@code riders} to riders when it changes world as asked. */
    public static @Nullable Destination ask(Entity entity, @Nullable ServerLevel toLevel, double x, double y, double z,
            float yRot, float xRot, EntityTeleportEvent.Riders riders) {
        Destination requested = new Destination(toLevel != null ? toLevel
                : entity.level() instanceof ServerLevel own ? own : null, x, y, z, yRot, xRot);
        if (!(entity.level() instanceof ServerLevel from) || suppressed(entity)) return requested;
        boolean isMarked = marked == entity && markedTick == entity.tickCount;
        TeleportCause cause = isMarked && markedCause != null ? markedCause : TeleportCause.UNKNOWN;
        boolean asked = isMarked && markedAsked;
        Destination override = isMarked ? markedOverride : null;
        // A mark covers exactly the next teleport: an approval never carries over to a second teleport this tick.
        if (isMarked) {
            marked = null;
            markedOverride = null;
        }
        if (asked) {
            // NeoForge's event already moved the position; the level and facing it can't carry are applied here.
            return override == null ? requested
                    : new Destination(override.level(), x, y, z, override.yRot(), override.xRot());
        }
        if (!Hooks.listens(EntityTeleportEvent.class)) return requested;
        EntityTeleportEvent event = Hooks.post(new EntityTeleportEvent(entity, toLevel != null ? toLevel : from,
                new Vec3(x, y, z), yRot, xRot, cause, riders, actor(entity, cause)));
        if (event.isCancelled()) return null;
        leave(entity);
        expect(entity, event);
        Vec3 to = event.getTo();
        return new Destination(event.getToLevel(), to.x, to.y, to.z, event.getToYRot(), event.getToXRot());
    }

    /**
     * Sends an entity where a listener redirected it. Runs as part of the teleport already asked about, so it doesn't
     * ask again. Within a level, or for a player, through the teleport method that takes a level, position and facing;
     * any other entity crosses levels through {@code changeDimension}, since that teleport method leaves its
     * passengers behind.
     */
    public static boolean redirect(Entity entity, Destination to) {
        if (to.crossesLevel(entity) && !(entity instanceof ServerPlayer)) {
            return during(entity, () -> {
                Entity arrived = entity.changeDimension(new DimensionTransition(to.level(), to.position(),
                        entity.getDeltaMovement(), to.yRot(), to.xRot(), DimensionTransition.DO_NOTHING));
                if (arrived == null) return false;
                arrived.setXRot(to.xRot()); // changeDimension keeps the old pitch
                arrived.setYHeadRot(to.yRot());
                return true;
            });
        }
        return during(entity, () -> entity.teleportTo(to.level(), to.x(), to.y(), to.z(), Set.of(), to.yRot(),
                to.xRot()));
    }

    /** {@code LivingEntity.randomTeleport} with the coordinates it searches from; true when it found room. */
    @FunctionalInterface
    public interface RandomTeleport {
        boolean attempt(double x, double y, double z, boolean effects);
    }

    /** Whether {@code entity}'s random teleport search is running as a probe, so it must only be placed. */
    public static boolean probing(Entity entity) {
        return probing == entity;
    }

    /**
     * Places an entity for a probe: its position and box only, not through its own {@code setPos}, which can change
     * more than where it is (a shulker closes its shell and rounds to a block's centre). Put back the same way, it is
     * as it was. Where it would really end up is {@code Landing.of} the spot found, which the event states.
     */
    public static void probePlace(Entity entity, double x, double y, double z) {
        entity.setPosRaw(x, y, z);
        entity.setBoundingBox(((EntityMotionAccessor) entity).hadron$makeBoundingBox());
    }

    /**
     * A random teleport (an enderman, chorus fruit, a mod's {@code randomTeleport}). Vanilla's search runs first as a
     * probe: it places the entity (searching down for ground, checking for room, stepping back if there is none), with
     * no teleport, so nothing is sent or asked; it is then put back. A spot with room is asked about as where it lands,
     * and only then is the entity teleported there, exactly, by its own teleport method. A spot with none fires
     * nothing.
     */
    public static boolean randomTeleport(LivingEntity entity, double x, double y, double z, boolean effects,
            RandomTeleport original) {
        if (!(entity.level() instanceof ServerLevel level) || suppressed(entity)) {
            return original.attempt(x, y, z, effects);
        }
        boolean isMarked = marked == entity && markedTick == entity.tickCount;
        TeleportCause cause = isMarked && markedCause != null ? markedCause : TeleportCause.UNKNOWN;
        if (isMarked) {
            marked = null;
            markedOverride = null;
        }
        if (!Hooks.listens(EntityTeleportEvent.class)) {
            leave(entity);
            return during(entity, () -> original.attempt(x, y, z, effects));
        }
        Vec3 start = entity.position();
        Entity outer = probing;
        probing = entity;
        boolean found;
        try {
            found = original.attempt(x, y, z, false);
        } finally {
            probing = outer;
        }
        Vec3 foundAt = entity.position();
        probePlace(entity, start.x, start.y, start.z);
        if (!found) return false;
        Vec3 landing = Landing.of(entity, foundAt);
        float yRot = entity.getYRot();
        float xRot = entity.getXRot();
        EntityTeleportEvent event = Hooks.post(new EntityTeleportEvent(entity, level, landing, yRot, xRot, cause,
                actor(entity, cause)));
        if (event.isCancelled()) return false;
        leave(entity);
        expect(entity, event);
        Vec3 to = event.getTo();
        Destination going = new Destination(event.getToLevel(), to.x, to.y, to.z, event.getToYRot(),
                event.getToXRot());
        boolean went = going.differs(entity, level, landing.x, landing.y, landing.z, yRot, xRot)
                ? redirect(entity, going)
                : during(entity, () -> {
                    entity.teleportTo(landing.x, landing.y, landing.z);
                    return Boolean.TRUE;
                });
        if (!went) return false;
        if (entity.level() == level && !entity.isRemoved()) {
            if (effects) level.broadcastEntityEvent(entity, (byte) 46);
            if (entity instanceof PathfinderMob mob) mob.getNavigation().stop();
        }
        return true;
    }

    /**
     * Runs {@code body} (an ender pearl landing, chorus fruit being eaten), inside which a rider vanilla takes off
     * before asking about its teleport can wait for that teleport instead ({@link #leaveOnLanding}). A wait the
     * teleport never reached ends with it: the rider stays on.
     */
    public static <T> T leavingOnLanding(Supplier<T> body) {
        Leaving outer = leaving;
        try {
            return body.get();
        } finally {
            leaving = outer;
        }
    }

    /**
     * Vanilla is about to take {@code entity} off what it rides before asking about its teleport: when someone listens,
     * it waits, and {@code leave} runs once the teleport goes ahead. Returns whether it waits; when it doesn't, the
     * caller runs it now. Only inside {@link #leavingOnLanding}.
     */
    public static boolean leaveOnLanding(Entity entity, Runnable leave) {
        if (!(entity.level() instanceof ServerLevel) || !Hooks.listens(EntityTeleportEvent.class)) return false;
        leaving = new Leaving(entity, leave);
        return true;
    }

    /** Takes {@code entity} off what it rides if that waited for its teleport, as part of the teleport (forced). */
    static void leave(Entity entity) {
        Leaving l = leaving;
        if (l == null || l.entity() != entity) return;
        leaving = null;
        List<Entity> covered = cover(entity);
        try {
            l.leave().run();
        } finally {
            covered.forEach(travelling::remove);
        }
    }

    /** Who sends it: the entity itself when it walked into a portal or threw a pearl, otherwise the stack. */
    static Actor actor(Entity entity, TeleportCause cause) {
        return switch (cause) {
            case COMMAND, UNKNOWN -> EntityActors.fromStack(entity, Actor.Unknown.INSTANCE);
            default -> EntityActors.of(entity);
        };
    }

    /** Runs an approved teleport, so the methods it calls don't ask again, then resets the move check. */
    public static <T> T during(Entity entity, Supplier<T> teleport) {
        if (!(entity.level() instanceof ServerLevel) || suppressed(entity)) return teleport.get();
        List<Entity> covered = cover(entity);
        try {
            T result = teleport.get();
            // Put back by the landing check: a method that reports whether it teleported says it didn't, as when
            // its event is cancelled.
            if (landed(entity) && result instanceof Boolean) return refused();
            return result;
        } finally {
            covered.forEach(travelling::remove);
            EntityMoves.retrack(entity);
        }
    }

    /** Marks an entity and its passengers as travelling; returns those it added, to take off again after. */
    private static List<Entity> cover(Entity entity) {
        List<Entity> added = new ArrayList<>();
        if (travelling.add(entity)) added.add(entity);
        for (Entity passenger : entity.getIndirectPassengers()) {
            if (travelling.add(passenger)) added.add(passenger);
        }
        return added;
    }

    /** What a teleport method that reports whether it went returns when it didn't. */
    @SuppressWarnings("unchecked")
    private static <T> T refused() {
        return (T) Boolean.FALSE;
    }

    /** Moves an entity without firing teleport events: the port putting back what a listener refused. */
    static void quietly(Entity entity, Runnable move) {
        List<Entity> covered = cover(entity);
        try {
            move.run();
        } finally {
            covered.forEach(travelling::remove);
        }
    }


    /** Marks the next teleport of this entity, this tick, as a portal's. */
    public static void portal(Entity entity) {
        if (entity.level() instanceof ServerLevel) mark(entity, TeleportCause.PORTAL, false, null);
    }
}
