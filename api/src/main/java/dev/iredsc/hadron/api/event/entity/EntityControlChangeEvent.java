package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/**
 * Who controls an entity is about to change without anyone mounting it: a mob charmed or enthralled, a drone linked
 * to a monitor, a guard assigned. Cancel it and control stays as it was. Control running out is
 * {@link EntityControlExpireEvent}, which can't be cancelled.
 *
 * <p>Bridge event: core can't see these, so each mod's bridge fires it where the mod hands over control, filling in
 * the old and new controller as player UUIDs (empty for nobody), and then records the new controller with
 * {@code Actors.remember(entity, attribution.withController(...))} so later actions carry it.
 */
public class EntityControlChangeEvent extends EntityEvent implements Cancellable {
    private final UUID oldController;
    private final UUID newController;
    private boolean cancelled;

    public EntityControlChangeEvent(Entity entity, UUID oldController, UUID newController, Actor actor) {
        super(entity, actor);
        this.oldController = oldController;
        this.newController = newController;
    }

    /** Who controlled it; empty for nobody. */
    public Optional<UUID> getOldController() {
        return Optional.ofNullable(oldController);
    }

    /** Who will control it; empty when control is being given up. */
    public Optional<UUID> getNewController() {
        return Optional.ofNullable(newController);
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
