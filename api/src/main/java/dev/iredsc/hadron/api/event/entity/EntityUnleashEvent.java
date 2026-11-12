package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An actor is about to take the lead off an entity. Bukkit's {@code PlayerUnleashEntityEvent}. Cancel it and the
 * entity stays on the lead and no lead drops.
 *
 * <p>Not fired when a lead breaks on its own (stretched too far, its holder gone, the entity dead, mounted or moved to
 * another dimension): that is {@link EntityLeashBreakEvent}, after the fact, since refusing would leave an entity tied
 * to nothing.
 *
 * <p><b>Core fires it for a player taking the lead off an entity they hold.</b> A mod that unleashes in its own code
 * needs its bridge to fire this.
 */
@FiredBy(core = PARTIAL,
        coreSees = "a player taking the lead off an entity they hold",
        bridges = "the mod taking a lead off in its own code")
public class EntityUnleashEvent extends EntityEvent implements Cancellable {
    private final Entity holder;
    private boolean cancelled;

    public EntityUnleashEvent(Entity entity, Entity holder, Actor actor) {
        super(entity, actor);
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    /** What holds the lead now: a player, or a knot on a fence. */
    public Entity getHolder() {
        return holder;
    }

    /**
     * The player behind it, if online: directly, or the owner of the fake player that did. Same as
     * {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /** Where the leashed entity is, then where what held the lead is. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.of(getHolder()));
    }
}
