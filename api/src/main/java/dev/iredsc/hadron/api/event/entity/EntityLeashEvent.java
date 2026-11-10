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
 * An entity is about to be put on a lead, or a lead it is on is about to be tied to a fence. Bukkit's
 * {@code PlayerLeashEntityEvent}. Cancel it and the entity stays as it was: unleashed with the lead still in hand, or
 * still held by the player rather than tied.
 *
 * <p><b>Core fires it for players only:</b> putting a lead on a mob or a boat, and tying the ones they hold to a
 * fence. A trader's llamas, leashed as they spawn, fire {@link EntitySpawnEvent} instead. A mod that leashes in its
 * own code needs its bridge to fire this.
 */
@FiredBy(core = PARTIAL,
        coreSees = "players putting a lead on a mob or boat, and tying the ones they hold to a fence",
        bridges = "the mod leashing in its own code (its own lead items, leashing what it spawns)")
public class EntityLeashEvent extends EntityEvent implements Cancellable {
    private final Entity holder;
    private boolean cancelled;

    public EntityLeashEvent(Entity entity, Entity holder, Actor actor) {
        super(entity, actor);
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    /** What will hold the lead: the player, or the knot on the fence it is tied to. */
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

    /** Where the leashed entity is, then where what holds the lead is. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.of(getHolder()));
    }
}
