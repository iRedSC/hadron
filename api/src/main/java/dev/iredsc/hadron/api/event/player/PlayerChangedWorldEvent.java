package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Places;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player has moved to another world (dimension); {@code getPlayer().serverLevel()} is the new one. After the fact.
 * {@link #getFrom()} and {@link #getFromPosition()} say where they stood just before they left.
 */
@FiredBy(core = FULL)
public class PlayerChangedWorldEvent extends PlayerEvent {
    private final ServerLevel from;
    private final Vec3 fromPosition;

    /**
     * @param from the world they left
     * @param fromPosition where in it they stood just before leaving
     */
    public PlayerChangedWorldEvent(ServerPlayer player, ServerLevel from, Vec3 fromPosition) {
        super(player);
        this.from = Objects.requireNonNull(from, "from");
        this.fromPosition = Objects.requireNonNull(fromPosition,
                "fromPosition; record the player's position before they leave their world");
    }

    /** The world they left. */
    public ServerLevel getFrom() {
        return from;
    }

    /** Where in {@link #getFrom()} they stood just before leaving. */
    public Vec3 getFromPosition() {
        return fromPosition;
    }

    /** Where the player is now, then the block they stood in in the world they left. */
    @Override
    public Places getPlaces() {
        return super.getPlaces().and(Place.Spot.at(from, fromPosition));
    }
}
