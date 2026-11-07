package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.data.PersistentData;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Where {@code PersistentData.of(...)} keeps each holder's data on NeoForge, and the piston carry. */
final class DataStore implements PersistentData.Port.Store {
    @Override
    public PersistentData.Port.Root entity(Entity entity) {
        return new EntityRoot(entity);
    }

    @Override
    public PersistentData.Port.Root blockEntity(BlockEntity blockEntity) {
        return new BlockEntityRoot(blockEntity);
    }

    @Override
    public PersistentData.Port.Root block(ServerLevel level, BlockPos pos) {
        return new BlockRoot(level, pos);
    }

    @Override
    public PersistentData.Port.Root chunk(LevelChunk chunk) {
        return new ChunkRoot(chunk);
    }

    @Override
    public PersistentData.Port.Root level(ServerLevel level) {
        return new LevelRoot(level);
    }

    @Override
    public void move(ServerLevel level, BlockPos from, BlockPos to) {
        serverThread(level, "blocks");
        if (from.equals(to)) return;
        CompoundTag tag = DataBlocks.take(level.getChunkAt(from), from);
        LevelChunk target = level.getChunkAt(to);
        DataBlocks.put(target, to, tag);
        // If the block isn't at `to` yet, its arrival must not clear what was just moved there.
        if (tag != null && !tag.isEmpty() && level.getBlockState(to).getBlock() != level.getBlockState(from).getBlock()) {
            DataBlocks.expectArrival(target, to);
        }
    }

    private static void serverThread(Level level, String holder) {
        if (!(level instanceof ServerLevel server)) {
            throw new IllegalArgumentException("Persistent data lives on the server, and these " + holder + " are "
                    + "the client's copy; use it from server-side code, where level.isClientSide() is false");
        }
        if (!server.getServer().isSameThread()) {
            throw new IllegalStateException("Persistent data on " + holder + " is server-thread only, and this is "
                    + "thread \"" + Thread.currentThread().getName() + "\". Do it on the server thread: "
                    + "getServer().getScheduler().runTask(plugin, () -> ...)");
        }
    }

    private record EntityRoot(Entity entity) implements PersistentData.Port.Root {
        @Override
        public CompoundTag read() {
            serverThread(entity.level(), "entities");
            return entity.getExistingDataOrNull(DataPort.DATA.get());
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            serverThread(entity.level(), "entities");
            Entity.RemovalReason removed = entity.getRemovalReason();
            if (removed != null && !removed.shouldDestroy()) {
                throw new IllegalStateException(entity + " was unloaded or changed dimension, so data written to "
                        + "this copy would be lost; look the entity up again, e.g. level.getEntity(uuid)");
            }
            CompoundTag tag = entity.getData(DataPort.DATA.get());
            try {
                edit.accept(tag);
            } finally {
                if (tag.isEmpty()) entity.removeData(DataPort.DATA.get());
            }
        }
    }

    private record BlockEntityRoot(BlockEntity blockEntity) implements PersistentData.Port.Root {
        private void check() {
            // One not placed yet has no level; its data is saved once it is.
            if (blockEntity.getLevel() != null) serverThread(blockEntity.getLevel(), "block entities");
        }

        @Override
        public CompoundTag read() {
            check();
            return blockEntity.getExistingDataOrNull(DataPort.DATA.get());
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            check();
            if (blockEntity.isRemoved()) {
                throw new IllegalStateException("The block entity at " + blockEntity.getBlockPos().toShortString()
                        + " was removed, so data written to it would be lost; get it again with "
                        + "level.getBlockEntity(pos)");
            }
            CompoundTag tag = blockEntity.getData(DataPort.DATA.get());
            try {
                edit.accept(tag);
            } finally {
                if (tag.isEmpty()) blockEntity.removeData(DataPort.DATA.get());
                blockEntity.setChanged();
            }
        }
    }

    private record BlockRoot(ServerLevel level, BlockPos pos) implements PersistentData.Port.Root {
        @Override
        public CompoundTag read() {
            serverThread(level, "blocks");
            return DataBlocks.get(level.getChunkAt(pos), pos);
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            serverThread(level, "blocks");
            DataBlocks.edit(level.getChunkAt(pos), pos, edit);
        }
    }

    private record ChunkRoot(LevelChunk chunk) implements PersistentData.Port.Root {
        @Override
        public CompoundTag read() {
            serverThread(chunk.getLevel(), "chunks");
            return chunk.getExistingDataOrNull(DataPort.DATA.get());
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            serverThread(chunk.getLevel(), "chunks");
            ServerLevel level = (ServerLevel) chunk.getLevel();
            if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) != chunk) {
                throw new IllegalStateException("Chunk " + chunk.getPos() + " was unloaded, so data written to this "
                        + "copy would be lost; get the loaded one with PersistentData.of(level.getChunk(x, z))");
            }
            CompoundTag tag = chunk.getData(DataPort.DATA.get());
            try {
                edit.accept(tag);
            } finally {
                if (tag.isEmpty()) chunk.removeData(DataPort.DATA.get());
                chunk.setUnsaved(true);
            }
        }
    }

    /** Saved by NeoForge's level attachments, which are written on every level save. */
    private record LevelRoot(ServerLevel level) implements PersistentData.Port.Root {
        @Override
        public CompoundTag read() {
            serverThread(level, "worlds");
            return level.getExistingDataOrNull(DataPort.DATA.get());
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            serverThread(level, "worlds");
            CompoundTag tag = level.getData(DataPort.DATA.get());
            try {
                edit.accept(tag);
            } finally {
                if (tag.isEmpty()) level.removeData(DataPort.DATA.get());
            }
        }
    }
}
