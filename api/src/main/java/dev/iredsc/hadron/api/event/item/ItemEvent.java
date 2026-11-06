package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
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
    private final ItemStack item;

    protected ItemEvent(ServerLevel level, ItemStack item, Actor actor) {
        super(actor);
        this.level = Objects.requireNonNull(level, "level");
        this.item = Objects.requireNonNull(item, "item; use ItemStack.EMPTY");
    }

    /** Where it happens. */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * The stack this is about. Each event says whether it is the live stack or a copy; change it only where the
     * event says so, and cancel to stop the action instead.
     */
    public ItemStack getItem() {
        return item;
    }

    /**
     * The player who did it, if a player is behind it and online: directly, or as the owner of the machine, mob or
     * fake player that did. Empty for a dispenser with no owner, a wild mob, or nature. Same as
     * {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
    }
}
