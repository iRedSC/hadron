package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An entity is about to leave the world other than by dying, unloading, changing dimension or converting into another
 * kind: {@code /kill} on a boat, a minecart, an item, an armor stand or the ender dragon, a summon dismissed, a fish
 * caught in a bucket ({@link EntityCaptureEvent}). Cancel it and the entity stays exactly as it was.
 *
 * <p>Not fired for an entity that dies: {@code /kill} on a pig hurts it and fires {@link EntityDamageEvent} and
 * {@link EntityDeathEvent} instead. Not fired for {@link EntityTransformEvent}'s conversions, nor for entities that
 * run out on their own (a mob despawning, a firework ending).
 *
 * <p><b>Core fires it for {@code /kill}, water buckets and end crystals shattering only.</b> A mod that removes an
 * entity in its own code (dismissing a summon, an instant kill, a conversion of its own) needs its bridge to fire
 * this. Without one, the entity is gone and nothing heard it: Minecraft's removal method is called by item pickups and
 * the ends of explosions too, so refusing there would duplicate things.
 */
@FiredBy(core = PARTIAL,
        coreSees = "/kill on entities that don't die (boats, minecarts, items, armor stands, the ender dragon), end crystals shattering",
        bridges = "the mod removing an entity in its own code: dismissing a summon, an instant kill, its own conversion")
public class EntityRemoveEvent extends EntityEvent implements Cancellable {
    /** Why an entity is leaving. Bukkit's name for the same idea. */
    public enum Cause {
        /**
         * Killed without being hurt: {@code /kill} on an entity that doesn't die, a hit shattering an end crystal, a
         * mod's instant kill.
         */
        KILLED,
        /** Sent away by whoever summoned or owns it: a dismissed summon, a recalled drone. */
        DISMISSED,
        /** Replaced by another entity in a mod's own code, not through vanilla's conversion. */
        CONVERTED,
        /** Stored into an item, block or carry state; always an {@link EntityCaptureEvent}. */
        CAPTURED,
        /** Removed for any other reason. */
        DISCARDED
    }

    private final Cause cause;
    private boolean cancelled;

    /**
     * @throws IllegalArgumentException for {@link Cause#CAPTURED}: fire {@link EntityCaptureEvent} instead, which says
     *     where the entity went
     */
    public EntityRemoveEvent(Entity entity, Cause cause, Actor actor) {
        super(entity, actor);
        this.cause = Objects.requireNonNull(cause, "cause; use Cause.DISCARDED when no other fits");
        if (cause == Cause.CAPTURED) {
            throw new IllegalArgumentException("A capture says where the entity went: fire new EntityCaptureEvent("
                    + "entity, storage, actor) instead of EntityRemoveEvent with Cause.CAPTURED");
        }
    }

    /** For {@link EntityCaptureEvent}, the one event whose cause is {@link Cause#CAPTURED}. */
    EntityRemoveEvent(Entity entity, Actor actor) {
        super(entity, actor);
        this.cause = Cause.CAPTURED;
    }

    /** Why it is leaving. */
    public Cause getCause() {
        return cause;
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
