package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

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
 */
public class PlayerDeathEvent extends EntityDeathEvent {
    private final List<ItemStack> kept;

    public PlayerDeathEvent(ServerPlayer player, DamageSource source, List<ItemStack> drops, List<ItemStack> kept,
            int droppedExp, Actor actor) {
        super(player, source, drops, droppedExp, actor);
        this.kept = Objects.requireNonNull(kept, "kept");
    }

    /** The player who is dying. */
    public ServerPlayer getPlayer() {
        return (ServerPlayer) getEntity();
    }

    /** Items given back to the player when they respawn. Editable. */
    public List<ItemStack> getItemsToKeep() {
        return kept;
    }
}
