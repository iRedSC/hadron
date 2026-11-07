package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A block forms from the conditions around it: snow or ice from weather, cobblestone, stone, obsidian or basalt where
 * fluids meet, copper weathering. Cancel it and it doesn't form.
 */
@FiredBy(core = FULL)
public class BlockFormEvent extends BlockGrowEvent {
    public BlockFormEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        super(level, pos, before, after, actor);
    }

    public BlockFormEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change, actor);
    }
}
