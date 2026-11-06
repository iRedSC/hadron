package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Leaves far from any log are about to decay. Cancel it and they stay, with nothing dropped. */
public class LeavesDecayEvent extends BlockChangeEvent {
    public LeavesDecayEvent(ServerLevel level, BlockPos pos, BlockState leaves, BlockState after, Actor actor) {
        super(level, pos, leaves, List.of(BlockChange.of(level, pos, leaves, after)), actor);
    }
}
