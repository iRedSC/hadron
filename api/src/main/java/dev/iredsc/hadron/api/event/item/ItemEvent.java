package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.EventStack;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Anything that happens to an item stack: dropped, picked up, used, eaten, damaged, enchanted, crafted, smelted,
 * traded, equipped. Listen for this to hear all of them, from every actor.
 */
public abstract class ItemEvent extends ActionEvent {
    private final ServerLevel level;
    private final EventStack item;

    /**
     * @param item {@link EventStack#copyOf} for an event that hands out copies, {@link EventStack#live} for one whose
     *     Javadoc says {@link #getItem()} is the live stack
     */
    protected ItemEvent(ServerLevel level, EventStack item, Actor actor) {
        super(actor);
        this.level = Objects.requireNonNull(level, "level");
        this.item = Objects.requireNonNull(item, "item; use EventStack.copyOf(stack, name) or EventStack.live");
    }

    /** Where it happens. */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * The stack this is about. Each event says whether it is the live stack or a copy: a copy is new on every call, so
     * editing it changes nothing. Change the live stack only where the event says so, and cancel to stop the action
     * instead.
     */
    public ItemStack getItem() {
        return item.get();
    }

    /**
     * The player who did it, if a player is behind it and online: directly, or as the owner of the machine, mob or
     * fake player that did. Empty for a dispenser with no owner, a wild mob, or nature. Same as
     * {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }

    /**
     * Where it happens. An item has no place of its own, so each leaf names where its stack is: the inventory it is
     * in, the entity holding or using it, where it lies. A leaf that names none (a bridge's own) is
     * {@link Place.Unknown} within {@link #getLevel()}.
     */
    @Override
    public Places getPlaces() {
        return Places.of(new Place.Unknown(new Place.Level(getLevel()), Place.Unknown.Reason.UNPLACEABLE_OPERATION));
    }
}
