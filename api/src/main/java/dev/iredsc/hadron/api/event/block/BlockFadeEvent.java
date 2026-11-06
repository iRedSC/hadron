package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block fades or decays on its own: ice or snow melting, fire burning out, coral dying, farmland drying, grass
 * dying under a block, a cactus breaking off. Cancel it and it stays as it is, with nothing dropped.
 */
public class BlockFadeEvent extends BlockChangeEvent {
    public BlockFadeEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        super(level, pos, before, after, actor);
    }

    public BlockFadeEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change, actor);
    }

    /** The state after fading; same as {@link #getAfter()}. */
    public BlockState getNewState() {
        return getAfter();
    }
}
