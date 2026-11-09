package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A lead came off an entity without anyone taking it off: stretched too far, its holder gone, the entity dead,
 * mounted or moved to another dimension. Bukkit's {@code EntityUnleashEvent} for those reasons. After the fact and not
 * cancellable, so a plugin can't leave an entity tied to something that is gone. A player taking a lead off is
 * {@link EntityUnleashEvent}, which can be refused.
 *
 * <p>The actor is whoever is on the stack when it happens (a player whose click removed the knot), otherwise nature.
 *
 * <p><b>Core fires it for every reason below.</b> A lead a mod drops in its own code with none of these true (a
 * despawn, its own unleashing) fires nothing unless its bridge fires {@link EntityUnleashEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "leads snapping from distance, a holder dying or removed, the entity dying, mounting or changing dimension",
        bridges = "the mod dropping a lead in its own code for any other reason, as EntityUnleashEvent")
public class EntityLeashBreakEvent extends EntityEvent {
    /** Why the lead came off. */
    public enum Reason {
        /** Stretched past ten blocks. */
        DISTANCE,
        /** The holder died or left the world: a knot broken, a player dead or gone. */
        HOLDER_GONE,
        /** The leashed entity died or left the world. */
        ENTITY_GONE,
        /** The leashed entity went through a portal; its copy in the new dimension is free. */
        CHANGED_DIMENSION,
        /** The leashed entity started riding something. */
        MOUNTED
    }

    private final Entity holder;
    private final Reason reason;

    public EntityLeashBreakEvent(Entity entity, Entity holder, Reason reason, Actor actor) {
        super(entity, actor);
        this.holder = Objects.requireNonNull(holder, "holder");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** What held the lead until now: a player, a knot on a fence, a wandering trader. */
    public Entity getHolder() {
        return holder;
    }

    public Reason getReason() {
        return reason;
    }
}
