package dev.iredsc.hadron.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** What the {@code mixin.data} piston mixins call: block-position data moves with pushed blocks. */
public final class DataHooks {
    private DataHooks() {}

    /**
     * A piston made a moving block at its destination: the pushed block's data leaves its origin with it. Called
     * before anything overwrites the origin.
     */
    public static void pistonTake(Level level, BlockEntity created, Direction facing, boolean extending) {
        if (!(level instanceof ServerLevel server) || !(created instanceof PistonMovingBlockEntity moving)
                || moving.isSourcePiston()) {
            return;
        }
        BlockPos from = moving.getBlockPos().relative(extending ? facing.getOpposite() : facing);
        LevelChunk chunk = server.getChunkAt(from);
        if (DataBlocks.of(chunk) == null) return;
        CompoundTag tag = DataBlocks.take(chunk, from);
        if (tag != null) moving.setData(DataPort.MOVING.get(), tag);
    }

    /** A moving block landed: its data goes to where it stands now, if it survived the landing. */
    public static void pistonLand(Level level, PistonMovingBlockEntity moving) {
        if (!moving.hasData(DataPort.MOVING.get())) return;
        CompoundTag tag = moving.removeData(DataPort.MOVING.get());
        BlockPos pos = moving.getBlockPos();
        if (level instanceof ServerLevel && level.getBlockState(pos).is(moving.getMovedState().getBlock())) {
            DataBlocks.put(level.getChunkAt(pos), pos, tag);
        }
    }
}
