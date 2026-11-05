package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** A player is placing a block. Cancel it and the block isn't placed. */
public class BlockPlaceEvent extends BlockEvent implements Cancellable {
    private final ServerPlayer player;
    private boolean cancelled;

    public BlockPlaceEvent(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        super(level, pos, state);
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
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
