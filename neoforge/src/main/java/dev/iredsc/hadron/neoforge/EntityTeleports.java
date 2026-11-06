package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * One {@link EntityTeleportEvent} per teleport, however many of Minecraft's teleport methods it passes through. The
 * outermost teleport method asks; anything nested inside it (a player's cross-dimension teleport calling
 * {@code changeDimension}, passengers following their vehicle) is part of that one teleport. Server thread only:
 * every entry point checks for a {@link ServerLevel} first.
 */
public final class EntityTeleports {
    /** The entity whose teleport is in progress, or null. */
    private static Entity active;
    /** Above zero while the port moves something itself (putting back a cancelled move). */
    private static int quiet;
    /** A cause NeoForge told us about for this entity in this tick, and whether it was already asked. */
    private static Entity marked;
    private static int markedTick;
    private static TeleportCause markedCause;
    private static boolean markedAsked;
    /** Where a listener on NeoForge's path sent it, when that changed the level or facing; null otherwise. */
    private static Destination markedOverride;

    private EntityTeleports() {}

    /** Whether a teleport is under way, so a dismount it causes can't be refused. */
    static boolean inProgress() {
        return active != null || quiet > 0;
    }

    /**
     * NeoForge named the cause of this entity's coming teleport; {@code asked} if we already fired the event, and
     * {@code override} the level and facing a listener chose there (NeoForge's event carries only the position).
     */
    static void mark(Entity entity, TeleportCause cause, boolean asked, Destination override) {
        marked = entity;
        markedTick = entity.tickCount;
        markedCause = cause;
        markedAsked = asked;
        markedOverride = override;
    }

    /** Where a teleport goes. {@code level} null means the entity's own level, on the client. */
    public record Destination(ServerLevel level, double x, double y, double z, float yRot, float xRot) {
        /** Whether this differs from the teleport the method was called with. */
        public boolean differs(Entity entity, ServerLevel toLevel, double x, double y, double z, float yRot,
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
    public static Destination ask(Entity entity, ServerLevel toLevel, double x, double y, double z, float yRot,
            float xRot) {
        Destination requested = new Destination(toLevel != null ? toLevel
                : entity.level() instanceof ServerLevel own ? own : null, x, y, z, yRot, xRot);
        if (!(entity.level() instanceof ServerLevel from) || active != null || quiet > 0) return requested;
        boolean isMarked = marked == entity && markedTick == entity.tickCount;
        TeleportCause cause = isMarked ? markedCause : TeleportCause.UNKNOWN;
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
                new Vec3(x, y, z), yRot, xRot, cause, actor(entity, cause)));
        if (event.isCancelled()) return null;
        Vec3 to = event.getTo();
        return new Destination(event.getToLevel(), to.x, to.y, to.z, event.getToYRot(), event.getToXRot());
    }

    /**
     * Sends an entity where a listener redirected it, through the one teleport method that takes a level, position
     * and facing. Runs as part of the teleport already asked about, so it doesn't ask again.
     */
    public static boolean redirect(Entity entity, Destination to) {
        return during(entity, () -> entity.teleportTo(to.level(), to.x(), to.y(), to.z(), Set.of(), to.yRot(),
                to.xRot()));
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
        if (!(entity.level() instanceof ServerLevel) || active != null || quiet > 0) return teleport.get();
        active = entity;
        try {
            return teleport.get();
        } finally {
            active = null;
            EntityMoves.retrack(entity);
        }
    }

    /** Moves something without firing teleport events: the port putting back what a listener refused. */
    static void quietly(Runnable move) {
        quiet++;
        try {
            move.run();
        } finally {
            quiet--;
        }
    }

    /** Marks the next teleport of this entity, this tick, as a portal's. */
    public static void portal(Entity entity) {
        if (entity.level() instanceof ServerLevel) mark(entity, TeleportCause.PORTAL, false, null);
    }
}
