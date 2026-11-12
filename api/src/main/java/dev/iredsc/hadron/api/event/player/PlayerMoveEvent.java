package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player has walked, swum, flown or fallen into another block, or something riding them (an entity a mod lets them
 * carry) has. Fired at most once per tick, only when a block changes. Cancel it and the player is put back where they
 * were. A player riding something fires the vehicle's {@link EntityMoveEvent} instead.
 */
@FiredBy(core = FULL)
public class PlayerMoveEvent extends EntityMoveEvent {
    public PlayerMoveEvent(ServerPlayer player, Vec3 from, Vec3 to) {
        super(player, from, to, new Actor.Player(player));
    }

    /** A move whose riders set out from {@code ridersFrom}, as on {@link EntityMoveEvent}. */
    public PlayerMoveEvent(ServerPlayer player, Vec3 from, Vec3 to, java.util.Map<net.minecraft.world.entity.Entity,
            Vec3> ridersFrom) {
        super(player, from, to, ridersFrom, new Actor.Player(player));
    }

    /** The player who moved. */
    public ServerPlayer getPlayer() {
        return (ServerPlayer) getEntity();
    }
}
