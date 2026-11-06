package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/**
 * Control over an entity ran out: a charm wore off, a drone's link timed out. After the fact and not cancellable, so
 * temporary control can never be made permanent.
 *
 * <p>Bridge event: fired by the mod's bridge when its control expires, with the controller who lost it.
 */
public class EntityControlExpireEvent extends EntityEvent {
    private final UUID controller;

    public EntityControlExpireEvent(Entity entity, UUID controller, Actor actor) {
        super(entity, actor);
        this.controller = controller;
    }

    /** Who lost control; empty if nobody was recorded. */
    public Optional<UUID> getController() {
        return Optional.ofNullable(controller);
    }
}
