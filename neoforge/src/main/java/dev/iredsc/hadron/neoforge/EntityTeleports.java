package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
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

    private EntityTeleports() {}

    /** Whether a teleport is under way, so a dismount it causes can't be refused. */
    static boolean inProgress() {
        return active != null || quiet > 0;
    }

    /** NeoForge named the cause of this entity's coming teleport; {@code asked} if we already fired the event. */
    static void mark(Entity entity, TeleportCause cause, boolean asked) {
        marked = entity;
        markedTick = entity.tickCount;
        markedCause = cause;
        markedAsked = asked;
    }

    /**
     * Fires the event for a teleport about to start, unless it is nested in one or already asked. True to go ahead.
     * {@code toLevel} null means the entity's own level.
     */
    public static boolean ask(Entity entity, ServerLevel toLevel, double x, double y, double z) {
        if (!(entity.level() instanceof ServerLevel from) || active != null || quiet > 0) return true;
        boolean isMarked = marked == entity && markedTick == entity.tickCount;
        if (isMarked && markedAsked) return true;
        if (!Hooks.listens(EntityTeleportEvent.class)) return true;
        TeleportCause cause = isMarked ? markedCause : TeleportCause.UNKNOWN;
        return !Hooks.post(new EntityTeleportEvent(entity, toLevel != null ? toLevel : from, new Vec3(x, y, z), cause,
                actor(entity, cause))).isCancelled();
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
        if (entity.level() instanceof ServerLevel) mark(entity, TeleportCause.PORTAL, false);
    }
}
