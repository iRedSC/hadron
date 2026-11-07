package dev.iredsc.hadron.api.data;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Bukkit's {@code getPersistentDataContainer()}, for Minecraft's own classes: {@code PersistentData.of(stack)}.
 * Data lives as long as what holds it, and every change is saved at once; there is no meta to write back.
 *
 * <pre>{@code
 * NamespacedKey placed = new NamespacedKey(plugin, "placed_by_player");
 * PersistentData.of(level, pos).set(placed, PersistentDataType.BOOLEAN, true);   // in a BlockPlaceEvent
 * boolean paid = PersistentData.of(level, pos).has(placed);                      // in a BlockBreakEvent
 * }</pre>
 *
 * <p><b>Threads.</b> Entities, block entities, block positions, chunks and levels are server-thread only, and using
 * their containers from another thread throws. Item stacks may be used on any thread, but like the stack itself,
 * from one thread at a time.
 *
 * <p><b>Unloaded worlds.</b> A container keeps the {@code ServerLevel} it was made from. Once that world is unloaded
 * (with {@code Worlds.unload}), writing through it throws, since that copy is never saved again, even after the world
 * is loaded once more as a new {@code ServerLevel}: get the container again from the loaded one.
 */
public final class PersistentData {
    private static volatile Port.Store store = new Port.Missing();

    private PersistentData() {}

    /**
     * Data on an item stack, written into the stack as vanilla custom data, so it travels with the item and stacks
     * with different data don't stack. Works on any stack, even one not in the world yet.
     *
     * @throws IllegalArgumentException on writing to an empty stack
     */
    public static PersistentDataContainer of(ItemStack stack) {
        return new TagContainer(new ItemRoot(Objects.requireNonNull(stack, "stack")));
    }

    /** Data on an entity, saved with it; a player keeps theirs across death. Server-thread only. */
    public static PersistentDataContainer of(Entity entity) {
        return new TagContainer(store.entity(Objects.requireNonNull(entity, "entity")));
    }

    /** Data on a block entity (a chest, a furnace, a mod's machine), saved with it. Server-thread only. */
    public static PersistentDataContainer of(BlockEntity blockEntity) {
        return new TagContainer(store.blockEntity(Objects.requireNonNull(blockEntity, "blockEntity")));
    }

    /**
     * Data on the block at a position, with or without a block entity, kept apart from the block entity's own.
     * Removed when the block there is broken or replaced by a different block (a change of state, like a door
     * opening, keeps it), and moved with the block by pistons. Stored in the chunk, which this loads if needed.
     * Server-thread only.
     */
    public static PersistentDataContainer of(ServerLevel level, BlockPos pos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        if (level.isOutsideBuildHeight(pos)) {
            throw new IllegalArgumentException(pos.toShortString() + " is outside " + level.dimension().location()
                    + "'s build height (" + level.getMinBuildHeight() + " to " + (level.getMaxBuildHeight() - 1)
                    + "), where no block can hold data");
        }
        return new TagContainer(store.block(level, pos.immutable()));
    }

    /** Data on a whole chunk, saved with it. Get it again after the chunk unloads. Server-thread only. */
    public static PersistentDataContainer of(LevelChunk chunk) {
        return new TagContainer(store.chunk(Objects.requireNonNull(chunk, "chunk")));
    }

    /** Data on a world, saved with it. Server-thread only. */
    public static PersistentDataContainer of(ServerLevel level) {
        return new TagContainer(store.level(Objects.requireNonNull(level, "level")));
    }

    /**
     * For bridges whose machines move blocks (contraptions, frames): moves the data of the block at {@code from} to
     * {@code to}, replacing what {@code to} had, and leaves {@code from} with none. Call it before the block leaves
     * {@code from} (removing it clears its data), in the same tick, whether or not it has reached {@code to} yet: its
     * arrival keeps the data.
     * For a block that leaves the world for longer than a tick, {@link PersistentDataContainer#copyTo} its data into {@link #newContainer()}
     * first and back afterwards. Pistons need nothing: Hadron moves their blocks' data itself. Server-thread only.
     */
    public static void move(ServerLevel level, BlockPos from, BlockPos to) {
        store.move(Objects.requireNonNull(level, "level"), Objects.requireNonNull(from, "from").immutable(),
                Objects.requireNonNull(to, "to").immutable());
    }

    /** A container that belongs to nothing: to build a nested one, or to carry data while its holder is gone. */
    public static PersistentDataContainer newContainer() {
        return TagContainer.newDetached();
    }

    /** Hadron's data on an item: {@code custom_data.hadron.<namespace>.<key>}. */
    private record ItemRoot(ItemStack stack) implements Port.Root {
        private static final String KEY = "hadron";

        @Override
        public CompoundTag read() {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && data.getUnsafe().get(KEY) instanceof CompoundTag tag ? tag : null;
        }

        @Override
        public void write(Consumer<CompoundTag> edit) {
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("Can't store data on an empty item stack; check stack.isEmpty() "
                        + "first, or put the data on the stack before it's emptied");
            }
            CustomData.update(DataComponents.CUSTOM_DATA, stack, custom -> {
                CompoundTag tag = custom.get(KEY) instanceof CompoundTag existing ? existing : new CompoundTag();
                edit.accept(tag);
                if (tag.isEmpty()) custom.remove(KEY);
                else custom.put(KEY, tag);
            });
        }
    }

    /** For the loader port only. */
    public static final class Port {
        private Port() {}

        public static void installStore(Store installed) {
            store = Objects.requireNonNull(installed, "store");
        }

        /** Where one holder's data lives: a compound of namespaces, each a compound of values by key. */
        public interface Root {
            /** The stored compound, or null when there is none. Callers never modify it. */
            CompoundTag read();

            /** Applies the edit to the stored compound, creating it first if needed, then saves it (or drops it, if
             * left empty). Throws on the wrong thread, before editing anything. */
            void write(Consumer<CompoundTag> edit);
        }

        /** The holders only a loader can store data on. */
        public interface Store {
            Root entity(Entity entity);

            Root blockEntity(BlockEntity blockEntity);

            Root block(ServerLevel level, BlockPos pos);

            Root chunk(LevelChunk chunk);

            Root level(ServerLevel level);

            void move(ServerLevel level, BlockPos from, BlockPos to);
        }

        /** Until a port installs its store. */
        static final class Missing implements Store {
            private static IllegalStateException missing() {
                return new IllegalStateException("Persistent data on worlds, blocks and entities needs the Hadron mod "
                        + "running; item stacks work without it");
            }

            @Override
            public Root entity(Entity entity) {
                throw missing();
            }

            @Override
            public Root blockEntity(BlockEntity blockEntity) {
                throw missing();
            }

            @Override
            public Root block(ServerLevel level, BlockPos pos) {
                throw missing();
            }

            @Override
            public Root chunk(LevelChunk chunk) {
                throw missing();
            }

            @Override
            public Root level(ServerLevel level) {
                throw missing();
            }

            @Override
            public void move(ServerLevel level, BlockPos from, BlockPos to) {
                throw missing();
            }
        }
    }
}
