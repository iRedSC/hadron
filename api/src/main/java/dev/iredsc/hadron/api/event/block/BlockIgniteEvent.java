package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fire is about to start in a space: flint and steel, a fire charge, lava, lightning, or fire spreading. Cancel it and
 * no fire appears, and an item used keeps its durability. {@link #getActor()} says who: the player or machine, the
 * lightning bolt, or {@link Actor.Nature} for lava and spreading fire.
 */
public class BlockIgniteEvent extends BlockChangeEvent {
    public BlockIgniteEvent(ServerLevel level, BlockPos pos, BlockState before, BlockState fire, Actor actor) {
        super(level, pos, before, fire, actor);
    }

    public BlockIgniteEvent(ServerLevel level, BlockChange change, Actor actor) {
        super(level, change, actor);
    }
}
