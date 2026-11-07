package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** Leaves far from any log are about to decay. Cancel it and they stay, with nothing dropped. */
@FiredBy(core = FULL)
public class LeavesDecayEvent extends BlockChangeEvent {
    public LeavesDecayEvent(ServerLevel level, BlockPos pos, BlockState leaves, BlockState after, Actor actor) {
        super(level, pos, leaves, List.of(BlockChange.of(level, pos, leaves, after)), actor);
    }
}
