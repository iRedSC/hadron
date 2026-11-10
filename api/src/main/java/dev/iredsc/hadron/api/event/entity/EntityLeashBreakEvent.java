package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A lead came off an entity without anyone taking it off: stretched too far, its holder gone or never found, the
 * entity dead, despawned, destroyed or mounted. Bukkit's {@code EntityUnleashEvent} for those
 * reasons. Fired once the lead is off; not cancellable, so a plugin can't leave an entity tied to something that is
 * gone. A player taking a lead off is {@link EntityUnleashEvent}, which can be refused.
 *
 * <p>The actor is whoever is on the stack when it happens (a player whose click removed the knot), otherwise nature.
 *
 * <p><b>Core fires it for every reason below.</b> A mod that drops a lead in its own code fires
 * {@link EntityUnleashEvent} when someone chose it, and this when nobody did (its own despawn, a summon running out).
 */
@FiredBy(core = PARTIAL,
        coreSees = "leads snapping from distance, a holder dying, removed or never found after loading or a portal, and "
                + "the entity dying, despawning, being destroyed or mounting",
        bridges = "the mod dropping a lead in its own code with nobody choosing it (its own despawn, a summon running "
                + "out); a chosen removal is EntityUnleashEvent")
public class EntityLeashBreakEvent extends EntityEvent {
    /** Why the lead came off. */
    public enum Reason {
        /** Stretched past ten blocks. */
        DISTANCE,
        /** The holder died or left the world: a knot broken, a player dead or gone. */
        HOLDER_GONE,
        /**
         * The entity came back from a save, or went through a portal, and its holder didn't turn up within five
         * seconds to take the lead up again: a player who logged off, a holder left behind in the old dimension. A
         * portal alone is no break: the entity's copy keeps the lead, and fires this only if the holder never follows.
         * {@link #getHolder()} is empty.
         */
        HOLDER_NOT_FOUND,
        /** The leashed entity died, despawned or was destroyed (a boat broken, a pig struck into a zombified piglin). */
        ENTITY_GONE,
        /** The leashed entity started riding something. */
        MOUNTED
    }

    private final @Nullable Entity holder;
    private final Reason reason;

    /**
     * @param holder what held the lead; null when it never loaded: always for {@link Reason#HOLDER_NOT_FOUND}, and for
     *     {@link Reason#ENTITY_GONE} when the entity was destroyed while still waiting for a saved holder
     */
    public EntityLeashBreakEvent(Entity entity, @Nullable Entity holder, Reason reason, Actor actor) {
        super(entity, actor);
        this.reason = Objects.requireNonNull(reason, "reason");
        if (reason == Reason.HOLDER_NOT_FOUND ? holder != null : holder == null && reason != Reason.ENTITY_GONE) {
            throw new IllegalArgumentException("Pass the holder unless it never loaded: null for HOLDER_NOT_FOUND, "
                    + "and null for ENTITY_GONE only when the entity was still waiting for a saved holder (got "
                    + holder + " for " + reason + ")");
        }
        this.holder = holder;
    }

    /**
     * What held the lead until now: a player, a knot on a fence, a wandering trader. Empty when the holder never
     * loaded: always for {@link Reason#HOLDER_NOT_FOUND}, and for {@link Reason#ENTITY_GONE} when the entity was
     * destroyed while still waiting for a saved holder.
     */
    public Optional<Entity> getHolder() {
        return Optional.ofNullable(holder);
    }

    public Reason getReason() {
        return reason;
    }

    /** Where the leashed entity is, then where what held the lead is, when known. */
    @Override
    public Places getPlaces() {
        Places own = super.getPlaces();
        return getHolder().map(h -> own.and(Place.of(h))).orElse(own);
    }
}
