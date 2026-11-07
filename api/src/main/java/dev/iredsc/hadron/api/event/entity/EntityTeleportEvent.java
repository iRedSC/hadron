package dev.iredsc.hadron.api.event.entity;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity is about to move other than by walking: a command, an ender pearl, chorus fruit, an enderman, a portal,
 * a mod's waystone or teleporter. Its passengers are listed here rather than getting their own events, and go wherever
 * it goes within a world. A move to another world that no listener redirected keeps vanilla's rule, which leaves riders
 * behind (a ridden pig sent with {@code /execute in ... run tp}); a redirect with {@link #setTo} always takes them. Cancel it and nothing moves; an ender pearl's fall damage is not dealt.
 *
 * <p>{@link #setTo} sends it somewhere else instead, in this world or another, with its passengers. A redirected ender
 * pearl still deals its fall damage. Chorus fruit and endermen sent elsewhere in the same world still settle on the
 * ground below the new spot, and stay put if there is none, as they do in vanilla; sent to another world, they go to
 * exactly the position given.
 */
@FiredBy(core = FULL)
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

    private ServerLevel toLevel;
    private Vec3 to;
    private float toYRot;
    private float toXRot;
    private final TeleportCause cause;
    private final List<Entity> passengers;
    private boolean cancelled;

    /** A teleport that keeps the entity's current facing. */
    public EntityTeleportEvent(Entity entity, ServerLevel toLevel, Vec3 to, TeleportCause cause, Actor actor) {
        this(entity, toLevel, to, entity.getYRot(), entity.getXRot(), cause, actor);
    }

    /** A teleport that also turns the entity to face {@code yRot} (yaw) and {@code xRot} (pitch). */
    public EntityTeleportEvent(Entity entity, ServerLevel toLevel, Vec3 to, float yRot, float xRot,
            TeleportCause cause, Actor actor) {
        super(entity, actor);
        this.toLevel = Objects.requireNonNull(toLevel, "toLevel");
        this.to = Objects.requireNonNull(to, "to");
        this.toYRot = yRot;
        this.toXRot = xRot;
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

    /** The yaw it will face on arrival, in degrees. */
    public float getToYRot() {
        return toYRot;
    }

    /** The pitch it will face on arrival, in degrees. */
    public float getToXRot() {
        return toXRot;
    }

    /** Send it here instead, facing the way it would have. Its passengers go with it. */
    public void setTo(ServerLevel level, Vec3 position) {
        setTo(level, position, toYRot, toXRot);
    }

    /** Send it here instead, facing {@code yRot} (yaw) and {@code xRot} (pitch). Its passengers go with it. */
    public void setTo(ServerLevel level, Vec3 position, float yRot, float xRot) {
        this.toLevel = Objects.requireNonNull(level, "level; use getToLevel() to stay in the same world");
        this.to = Objects.requireNonNull(position, "position");
        this.toYRot = yRot;
        this.toXRot = xRot;
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
