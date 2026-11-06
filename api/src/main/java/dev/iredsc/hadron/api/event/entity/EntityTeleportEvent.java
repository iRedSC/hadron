package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * An entity is about to move other than by walking: a command, an ender pearl, chorus fruit, an enderman, a portal,
 * a mod's waystone or teleporter. Its passengers travel with it and are listed here rather than getting their own
 * events. Cancel it and nothing moves; an ender pearl's fall damage is not dealt.
 */
public class EntityTeleportEvent extends EntityEvent implements Cancellable {
    /** What sent it. */
    public enum TeleportCause {
        COMMAND,
        ENDER_PEARL,
        CHORUS_FRUIT,
        /** An enderman or shulker teleporting itself. */
        RANDOM_TELEPORT,
        /** A nether, end or modded portal. */
        PORTAL,
        /** A mod or plugin, or anything else. */
        UNKNOWN
    }

    private final ServerLevel toLevel;
    private final Vec3 to;
    private final TeleportCause cause;
    private final List<Entity> passengers;
    private boolean cancelled;

    public EntityTeleportEvent(Entity entity, ServerLevel toLevel, Vec3 to, TeleportCause cause, Actor actor) {
        super(entity, actor);
        this.toLevel = Objects.requireNonNull(toLevel, "toLevel");
        this.to = Objects.requireNonNull(to, "to");
        this.cause = Objects.requireNonNull(cause, "cause; use TeleportCause.UNKNOWN when no other fits");
        this.passengers = List.copyOf(entity.getPassengers());
    }

    /** Where it was. Same as {@link #getPosition()}. */
    public Vec3 getFrom() {
        return getPosition();
    }

    /** The level it was in. Same as {@link #getLevel()}. */
    public ServerLevel getFromLevel() {
        return getLevel();
    }

    /** Where it is going. */
    public Vec3 getTo() {
        return to;
    }

    /** The level it is going to; differs from {@link #getFromLevel()} when it changes dimension. */
    public ServerLevel getToLevel() {
        return toLevel;
    }

    public TeleportCause getCause() {
        return cause;
    }

    /** Everything riding it, which travels with it. */
    public List<Entity> getPassengers() {
        return passengers;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
