package dev.iredsc.hadron.api.event.player;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player is dying and what happens to their items is being decided. Not cancellable. Every item they carried,
 * including what mods' extra slots drop, is in exactly one place when listeners finish:
 *
 * <ul>
 *   <li>{@link #getDrops()}: dropped where they died (a grave plugin takes them from here);
 *   <li>{@link #getItemsToKeep()}: given back when they respawn, even across a restart;
 *   <li>neither: destroyed.
 * </ul>
 *
 * Move a stack from one list to the other to change where it goes.
 *
 * <p>{@link #getDeathMessage()} is the line about to be shown on the death screen and broadcast (to the teams the
 * scoreboard says); change it with {@link #setDeathMessage}, or set null for none. It replaces vanilla's line.
 */
@FiredBy(core = FULL)
public class PlayerDeathEvent extends EntityDeathEvent {
    private final List<ItemStack> kept;
    private @Nullable Component deathMessage;

    public PlayerDeathEvent(ServerPlayer player, DamageSource source, List<ItemStack> drops, List<ItemStack> kept,
            int droppedExp, Actor actor) {
        this(player, source, drops, kept, droppedExp, null, actor);
    }

    /** @param deathMessage the line about to be shown, or null for none (the showDeathMessages rule is off) */
    public PlayerDeathEvent(ServerPlayer player, DamageSource source, List<ItemStack> drops, List<ItemStack> kept,
            int droppedExp, @Nullable Component deathMessage, Actor actor) {
        super(player, source, drops, droppedExp, actor);
        this.kept = Objects.requireNonNull(kept, "kept");
        this.deathMessage = deathMessage;
    }

    /** The player who is dying. */
    public ServerPlayer getPlayer() {
        return (ServerPlayer) getEntity();
    }

    /** Items given back to the player when they respawn. Editable. */
    public List<ItemStack> getItemsToKeep() {
        return kept;
    }

    /**
     * The line about to be shown on the death screen and broadcast, or null when none will be (the
     * {@code showDeathMessages} game rule is off).
     */
    public @Nullable Component getDeathMessage() {
        return deathMessage;
    }

    /**
     * Show this instead; null or an empty component shows none. Setting one while {@code showDeathMessages} is off
     * shows it anyway.
     */
    public void setDeathMessage(Component deathMessage) {
        this.deathMessage = deathMessage;
    }
}
