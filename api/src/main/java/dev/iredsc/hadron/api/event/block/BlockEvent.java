package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.event.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Anything about one block. Listen for this to hear all of them. */
public abstract class BlockEvent extends Event {
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState state;

    protected BlockEvent(ServerLevel level, BlockPos pos, BlockState state) {
        this.level = level;
        this.pos = pos.immutable();
        this.state = state;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }

    /** The block this event is about: the one being broken, or the one being placed. */
    public BlockState getState() {
        return state;
    }
}
