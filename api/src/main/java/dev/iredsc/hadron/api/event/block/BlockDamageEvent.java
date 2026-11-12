package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An actor is about to start mining a block: a player's first hit on it, or a fake player's. Bukkit's
 * {@code BlockDamageEvent}. Cancel it and mining doesn't start: the block isn't hit (redstone ore doesn't light, a
 * note block doesn't play, a dragon egg doesn't move, the tool's on-hit enchantments don't act), the server records
 * no progress, so finishing the swing breaks nothing, and other players see no crack. The miner's own client is sent
 * the block unchanged but, as with vanilla's own refusals (spawn protection), may keep drawing its crack while the
 * button is held.
 *
 * <p>A block that breaks in one hit still fires this first, then {@link BlockBreakEvent}. In creative a hit breaks
 * the block at once without starting to mine, so only {@link BlockBreakEvent} fires, as in Bukkit. Lasting damage a
 * block keeps (a cannonball cracking a wall) is {@link BlockCrackEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "players and fake players starting to mine",
        bridges = "starts mining a block that takes time to break (a drill, a mining laser)")
public class BlockDamageEvent extends BlockEvent implements Cancellable {
    private final EventStack.Copy itemInHand;
    private final Direction face;
    private boolean cancelled;

    public BlockDamageEvent(ServerLevel level, BlockPos pos, BlockState state, ItemStack itemInHand, Direction face,
            Actor actor) {
        super(level, pos, state, List.of(BlockChange.unchanged(level, pos, state)), actor);
        this.itemInHand = EventStack.copyOf(itemInHand, "itemInHand");
        this.face = Objects.requireNonNull(face, "face");
    }

    /** A copy of what it mines with; empty for a bare hand. */
    public ItemStack getItemInHand() {
        return itemInHand.get();
    }

    /** The face that was hit. */
    public Direction getBlockFace() {
        return face;
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
