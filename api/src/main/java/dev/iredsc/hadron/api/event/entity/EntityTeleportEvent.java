package dev.iredsc.hadron.api.event.entity;

import java.util.ArrayList;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity is about to move other than by walking: a command, an ender pearl, chorus fruit, an enderman, a portal,
 * a mod's waystone or teleporter. Cancel it and nothing moves; an ender pearl's fall damage is not dealt.
 *
 * <p>Whatever rides it and goes along is {@link #getCarried()}, rather than getting events of its own. Riders go
 * wherever it goes within a world. To another world, it depends on how it is sent, and {@link #getCarried()} says which
 * for the destination as it stands: through a portal riders go along; vanilla's teleport to another world (a ridden
 * pig sent with {@code /execute in ... run tp}) leaves them behind ({@link Riders#LEFT_BEHIND}), unless a listener
 * sends it to yet another world, which takes them; a player's own riders never change world with them.
 *
 * <p>{@link #setTo} sends it somewhere else instead, in this world or another. A redirected ender pearl still deals its
 * fall damage. Chorus fruit and endermen sent elsewhere in the same world still settle on the
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
    /**
     * What happens to its riders when it goes to another world, the one it was first sent to: the way it is sent
     * decides. Sent to any other world by a listener, riders go along whatever this says.
     */
    public enum Riders {
        /** They go along: a portal, {@code changeDimension}, a mod's teleporter built on it. */
        GO_ALONG,
        /** They are left behind: vanilla's {@code teleportTo} with a level, which ejects them first. */
        LEFT_BEHIND
    }

    private final TeleportCause cause;
    /** The world it was first sent to, before any listener's {@link #setTo}. */
    private final ServerLevel askedLevel;
    private final Riders riders;
    private boolean cancelled;

    /** A teleport that keeps the entity's current facing, whose riders go along to another world. */
    public EntityTeleportEvent(Entity entity, ServerLevel toLevel, Vec3 to, TeleportCause cause, Actor actor) {
        this(entity, toLevel, to, entity.getYRot(), entity.getXRot(), cause, actor);
    }

    /**
     * A teleport that also turns the entity to face {@code yRot} (yaw) and {@code xRot} (pitch), whose riders go along
     * to another world.
     */
    public EntityTeleportEvent(Entity entity, ServerLevel toLevel, Vec3 to, float yRot, float xRot,
            TeleportCause cause, Actor actor) {
        this(entity, toLevel, to, yRot, xRot, cause, Riders.GO_ALONG, actor);
    }

    /**
     * A teleport that turns the entity to face {@code yRot} and {@code xRot}, and says what happens to its riders when
     * it goes to {@code toLevel} and that is another world.
     */
    public EntityTeleportEvent(Entity entity, ServerLevel toLevel, Vec3 to, float yRot, float xRot,
            TeleportCause cause, Riders riders, Actor actor) {
        super(entity, actor);
        this.toLevel = Objects.requireNonNull(toLevel, "toLevel");
        this.askedLevel = toLevel;
        this.to = Objects.requireNonNull(to, "to");
        this.toYRot = yRot;
        this.toXRot = xRot;
        this.cause = Objects.requireNonNull(cause, "cause; use TeleportCause.UNKNOWN when no other fits");
        this.riders = Objects.requireNonNull(riders, "riders; Riders.GO_ALONG when they change world with it");
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

    /** Send it here instead, facing the way it would have. {@link #getCarried()} follows. */
    public void setTo(ServerLevel level, Vec3 position) {
        setTo(level, position, toYRot, toXRot);
    }

    /** Send it here instead, facing {@code yRot} (yaw) and {@code xRot} (pitch). {@link #getCarried()} follows. */
    public void setTo(ServerLevel level, Vec3 position, float yRot, float xRot) {
        this.toLevel = Objects.requireNonNull(level, "level; use getToLevel() to stay in the same world");
        this.to = Objects.requireNonNull(position, "position");
        this.toYRot = yRot;
        this.toXRot = xRot;
    }

    public TeleportCause getCause() {
        return cause;
    }

    /**
     * Everything riding it that goes along, riders of riders included, worked out for where it is going now: a
     * listener's {@link #setTo} changes it. Each ride ends up somewhere in one area around the destination, built from
     * the vehicle's and riders' boxes (see the event's places), whichever way the vehicle faces and whether or not a
     * rider has been seated yet. Always {@link Carried.Known}; empty when riders are left behind.
     */
    public Carried getCarried() {
        Entity entity = getEntity();
        List<Entity> going = new ArrayList<>();
        if (toLevel == getFromLevel()) {
            entity.getIndirectPassengers().forEach(going::add);
        } else if (toLevel != askedLevel || riders == Riders.GO_ALONG) {
            // Another world: riders go unless it is the one first asked for and that way leaves them behind.
            changingWorld(entity, going);
        }
        if (going.isEmpty()) return Carried.none();
        Place.Spot seats = seats(entity, going);
        List<Carried.Ride> rides = new ArrayList<>();
        for (Entity rider : going) rides.add(new Carried.Ride(rider, Place.of(rider), seats));
        return Carried.of(rides);
    }

    /**
     * Every block any rider's box can be in around {@link #getTo()}: the vehicle's box there, grown up by every
     * rider's height stacked (a rider of a rider sits on top), out by the widest rider, and as far as any rider now
     * sits from it. Built from boxes, not seats, so a rider not yet seated (just mounted) or turned another way on
     * arrival is still inside: wide is safe, narrow would be a hole.
     */
    private Place.Spot seats(Entity vehicle, List<Entity> riders) {
        double reach = vehicle.getBbWidth() / 2;
        double below = 0;
        double above = vehicle.getBbHeight();
        double widest = 0;
        for (Entity rider : riders) {
            Vec3 offset = rider.position().subtract(vehicle.position());
            reach = Math.max(reach, Math.sqrt(offset.x * offset.x + offset.z * offset.z));
            below = Math.min(below, offset.y);
            above += rider.getBbHeight();
            above = Math.max(above, offset.y + rider.getBbHeight());
            widest = Math.max(widest, rider.getBbWidth() / 2);
        }
        Vec3 to = getTo();
        double r = reach + widest;
        return new Place.Spot(getToLevel(), BoundingBox.fromCorners(BlockPos.containing(to.x - r, to.y + below, to.z - r),
                BlockPos.containing(to.x + r, to.y + above, to.z + r)));
    }

    /**
     * Who changes world with {@code vehicle}: each rider, then theirs, as {@code changeDimension} takes them; a player
     * changes world alone, so their own riders stay.
     */
    private static void changingWorld(Entity vehicle, List<Entity> out) {
        if (vehicle instanceof ServerPlayer) return;
        for (Entity rider : vehicle.getPassengers()) {
            out.add(rider);
            changingWorld(rider, out);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Where it leaves from, where it goes ({@link #getTo()}, as it stands now), then where each entity it carries is
     * ({@link #getCarried()}, as it stands now).
     */
    @Override
    public Places getPlaces() {
        List<Place> more = new ArrayList<>();
        more.add(Place.Spot.at(getToLevel(), getTo()));
        more.addAll(getCarried().places());
        return Places.of(Place.Spot.at(getFromLevel(), getFrom()), more);
    }
}
