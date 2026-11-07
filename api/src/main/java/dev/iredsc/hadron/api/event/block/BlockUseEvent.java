package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An actor is about to right-click or activate a block: open a chest, flip a lever, press a button, including through
 * a fake player or remotely through a linked terminal. Bukkit's {@code PlayerInteractEvent} on a block. Cancel it and
 * the block doesn't react; what the held item does on its own (placing a block against it) is a separate event.
 *
 * <p>Fired once per hand the click tries, as vanilla tries the main hand and then the off hand.
 */
@FiredBy(core = FULL)
public class BlockUseEvent extends BlockEvent implements Cancellable {
    private final InteractionHand hand;
    private final ItemStack item;
    private final BlockHitResult hit;
    private boolean cancelled;

    public BlockUseEvent(ServerLevel level, BlockPos pos, BlockState state, InteractionHand hand, ItemStack item,
            BlockHitResult hit, Actor actor) {
        super(level, pos, state, List.of(BlockChange.unchanged(level, pos, state)), actor);
        this.hand = hand;
        this.item = item.copy();
        this.hit = hit;
    }

    public InteractionHand getHand() {
        return hand;
    }

    /** A copy of the item in that hand; empty when the hand is empty. */
    public ItemStack getItem() {
        return item.copy();
    }

    /** Whether something is held in that hand. */
    public boolean hasItem() {
        return !item.isEmpty();
    }

    /** The face clicked. */
    public Direction getBlockFace() {
        return hit.getDirection();
    }

    /** Exactly where the block was clicked. */
    public BlockHitResult getHitResult() {
        return hit;
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
