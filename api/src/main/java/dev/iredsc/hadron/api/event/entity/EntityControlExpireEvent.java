package dev.iredsc.hadron.api.event.entity;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Control over an entity ran out: a charm wore off, a drone's link timed out. After the fact and not cancellable, so
 * temporary control can never be made permanent.
 *
 * <p>Bridge event: fired by the mod's bridge when its control expires, with the controller who lost it.
 */
@FiredBy(core = NONE,
        bridges = "changes who controls an entity without mounting (charm, thrall, drone link, guard assignment); "
                + "expiry is after-only",
        fillIn = "old and new controller")
public class EntityControlExpireEvent extends EntityEvent {
    private final @Nullable UUID controller;

    public EntityControlExpireEvent(Entity entity, @Nullable UUID controller, Actor actor) {
        super(entity, actor);
        this.controller = controller;
    }

    /** Who lost control; empty if nobody was recorded. */
    public Optional<UUID> getController() {
        return Optional.ofNullable(controller);
    }
}
