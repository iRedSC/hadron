package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block forms from the conditions around it: snow or ice from weather, cobblestone, stone, obsidian or basalt where
 * fluids meet, copper weathering. Cancel it and it doesn't form.
 */
public class BlockFormEvent extends BlockGrowEvent {
    public BlockFormEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState after, Actor actor) {
        super(level, pos, before, after, actor);
    }

    public BlockFormEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change, actor);
    }
}
