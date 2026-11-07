package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * Fire is about to destroy a block. Cancel it and the block survives this burn, and nothing it would set off (such as
 * TNT) is triggered. Fire may take the burned block's place; {@link #getAfter()} is air.
 */
@FiredBy(core = FULL)
public class BlockBurnEvent extends BlockChangeEvent {
    private final @Nullable BlockPos ignitingPos;

    public BlockBurnEvent(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockPos ignitingPos, Actor actor) {
        super(level, pos, state, List.of(BlockChange.of(level, pos, state, Blocks.AIR.defaultBlockState())), actor);
        this.ignitingPos = ignitingPos == null ? null : ignitingPos.immutable();
    }

    /** The fire burning it, when known. */
    public Optional<BlockPos> getIgnitingPos() {
        return Optional.ofNullable(ignitingPos);
    }
}
