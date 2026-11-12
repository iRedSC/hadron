package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

    private EntityTeleports() {}

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
        boolean attempt(double x, double y, double z);
    }

    /**
     * A random teleport (an enderman, chorus fruit) is one attempt: it moves to the spot, and steps back if it doesn't
     * fit there. Asked about once; the step back is part of the attempt and never asked about, so a listener refusing
     * other teleports can't strand the entity where it doesn't fit.
     */
    public static boolean randomTeleport(LivingEntity entity, double x, double y, double z, RandomTeleport original) {
        Destination to = ask(entity, null, x, y, z, entity.getYRot(), entity.getXRot());
        if (to == null) return false;
        if (to.crossesLevel(entity)) return redirect(entity, to);
        boolean turned = to.yRot() != entity.getYRot() || to.xRot() != entity.getXRot();
        return during(entity, () -> {
            boolean moved = original.attempt(to.x(), to.y(), to.z());
            if (moved && turned && entity.level() instanceof ServerLevel level) {
                entity.teleportTo(level, entity.getX(), entity.getY(), entity.getZ(), Set.of(), to.yRot(), to.xRot());
            }
            return moved;
        });
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
            return teleport.get();
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
