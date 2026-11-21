package dev.iredsc.hadron.api.event.entity;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An entity is entering the world for the first time: a mob spawning, an egg hatching, a baby being born, a summon, a
 * fish released from a bucket, an arrow fired, an item frame hung. Not fired for entities loading with their chunk,
 * moving between dimensions, or converting into another (that is {@link EntityTransformEvent}), nor for dropped
 * items, experience orbs and falling blocks, whose contents were already taken from somewhere: refusing them would
 * destroy it. Cancel it and the entity never appears; a spawn egg or item frame used by a player is not spent.
 *
 * <p>{@link #getCause()} says how it came to be, for every entity, a mod's included: put here by an item
 * ({@link Cause#PLACED}), fired or thrown ({@link Cause#LAUNCHED}), or anything else ({@link Cause#SPAWNED}). A
 * protection plugin treats a placement as building without knowing which entities are furniture:
 *
 * <pre>{@code
 * if (event.getCause() == EntitySpawnEvent.Cause.PLACED) { ... } // a boat, an armor stand, a mod's chair
 * }</pre>
 */
@FiredBy(core = FULL)
public class EntitySpawnEvent extends EntityEvent implements Cancellable {
    /** How an entity came to be. Closed: every spawn is exactly one of these. */
    public enum Cause {
        /**
         * Someone or something put it here with an item: the item's own use, by a player or a fake player, or a
         * dispenser's shot, brought it into the world, and it isn't a projectile. A boat, a minecart, an armor stand,
         * an item frame, a painting, an end crystal, a spawn egg's mob, a bucket's fish, a mod's furniture placed by
         * its item; also what the block such a use places makes at once (a golem from its last pumpkin). The actor is
         * who placed it. Bukkit's {@code EntityPlaceEvent} and {@code HangingPlaceEvent}.
         */
        PLACED,
        /**
         * Fired or thrown, to fly from where it appears: an arrow, a snowball, a thrown potion, a firework. Every
         * {@link ProjectileLaunchEvent} is this, whatever fired it.
         */
        LAUNCHED,
        /**
         * Any other way: a mob spawning on its own or from a spawner, a summon, a baby born, lightning, TNT lit.
         * {@link #getSpawnReason()} says which for a mob.
         */
        SPAWNED
    }

    private final Cause cause;
    private final @Nullable MobSpawnType reason;
    private boolean cancelled;

    /**
     * @param cause how it came to be; {@link Cause#SPAWNED} unless your mod's item or placer put it there
     *     ({@link Cause#PLACED}) or it was fired or thrown ({@link Cause#LAUNCHED})
     * @param reason null for entities that aren't mobs and mobs spawned without one
     */
    public EntitySpawnEvent(Entity entity, Cause cause, @Nullable MobSpawnType reason, Actor actor) {
        super(entity, actor);
        this.cause = Objects.requireNonNull(cause, "cause; use Cause.SPAWNED when it was neither placed with an item "
                + "nor fired or thrown");
        this.reason = reason;
    }

    /**
     * How it came to be: {@link Cause#PLACED} with an item, {@link Cause#LAUNCHED}, or {@link Cause#SPAWNED} any other
     * way. Stated for every entity, so a mod's furniture placed by its item is {@code PLACED} like a boat. Not
     * {@link #getSpawnReason()}, which is Minecraft's own reason and exists only for mobs.
     */
    public Cause getCause() {
        return cause;
    }

    /**
     * Why a mob spawned: natural, spawner, spawn egg, breeding, summoned, bucket (released from capture) and so on.
     * Empty for entities that aren't mobs (arrows, items, vehicles) and for mobs spawned without a reason, so it can't
     * say whether a boat or an armor stand was placed: {@link #getCause()} does, for every entity.
     */
    public Optional<MobSpawnType> getSpawnReason() {
        return Optional.ofNullable(reason);
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
