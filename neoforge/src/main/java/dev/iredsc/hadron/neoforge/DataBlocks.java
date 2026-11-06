package dev.iredsc.hadron.neoforge;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * One chunk's block-position data, by packed position. Saved as {@code [{pos: [x, y, z], data: {...}}]}. Kept only
 * while it holds something, so a chunk's {@link DataChunk} field is null unless some block in it has data.
 */
public final class DataBlocks {
    static final IAttachmentSerializer<ListTag, DataBlocks> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public DataBlocks read(IAttachmentHolder holder, ListTag list, HolderLookup.Provider provider) {
            DataBlocks blocks = new DataBlocks();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                int[] pos = entry.getIntArray("pos");
                CompoundTag data = entry.getCompound("data");
                if (pos.length == 3 && !data.isEmpty()) blocks.byPos.put(BlockPos.asLong(pos[0], pos[1], pos[2]), data);
            }
            // Chunks load (and are promoted from proto chunks) through here, so the field always matches.
            if (holder instanceof DataChunk chunk) chunk.hadron$setBlocks(blocks);
            return blocks;
        }

        @Override
        public ListTag write(DataBlocks blocks, HolderLookup.Provider provider) {
            if (blocks.byPos.isEmpty()) return null;
            ListTag list = new ListTag();
            for (Long2ObjectMap.Entry<CompoundTag> entry : blocks.byPos.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                CompoundTag item = new CompoundTag();
                item.putIntArray("pos", new int[] {BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)});
                item.put("data", entry.getValue().copy());
                list.add(item);
            }
            return list;
        }
    };

    private final Long2ObjectOpenHashMap<CompoundTag> byPos = new Long2ObjectOpenHashMap<>();
    /**
     * Positions a bridge moved data to before the block itself arrived, with the game time it did. The arrival
     * (a block write there in the same tick) keeps the data instead of clearing it, so {@code move} works whether
     * the bridge calls it before or after placing the block. Never saved.
     */
    private it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap arriving;

    DataBlocks() {}

    static DataBlocks of(LevelChunk chunk) {
        return ((DataChunk) chunk).hadron$blocks();
    }

    static CompoundTag get(LevelChunk chunk, BlockPos pos) {
        DataBlocks blocks = of(chunk);
        return blocks == null ? null : blocks.byPos.get(pos.asLong());
    }

    static void edit(LevelChunk chunk, BlockPos pos, Consumer<CompoundTag> edit) {
        DataBlocks blocks = orCreate(chunk);
        long key = pos.asLong();
        CompoundTag tag = blocks.byPos.get(key);
        boolean fresh = tag == null;
        if (fresh) tag = new CompoundTag();
        try {
            edit.accept(tag);
        } finally {
            if (tag.isEmpty()) blocks.byPos.remove(key);
            else if (fresh) blocks.byPos.put(key, tag);
            blocks.changed(chunk);
        }
    }

    /** Removes and returns the data at a position; null if it had none. */
    static CompoundTag take(LevelChunk chunk, BlockPos pos) {
        DataBlocks blocks = of(chunk);
        if (blocks == null) return null;
        CompoundTag tag = blocks.byPos.remove(pos.asLong());
        if (tag != null) blocks.changed(chunk);
        return tag;
    }

    /** Replaces the data at a position; null or empty leaves it with none. */
    static void put(LevelChunk chunk, BlockPos pos, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            take(chunk, pos);
            return;
        }
        DataBlocks blocks = orCreate(chunk);
        blocks.byPos.put(pos.asLong(), tag);
        blocks.changed(chunk);
    }

    /**
     * Data cleared while NeoForge captures block snapshots (a placement waiting for its event, a tree growing): kept
     * aside for the rest of the tick, and put back if the capture is rolled back. Server thread only.
     */
    private static final java.util.Map<LevelChunk, Long2ObjectOpenHashMap<CompoundTag>> STASH = new java.util.WeakHashMap<>();
    private static long stashTime = Long.MIN_VALUE;

    private static void stash(LevelChunk chunk, long key, CompoundTag tag) {
        long now = chunk.getLevel().getGameTime();
        if (now != stashTime) {
            STASH.clear();
            stashTime = now;
        }
        STASH.computeIfAbsent(chunk, c -> new Long2ObjectOpenHashMap<>()).put(key, tag);
    }

    /** From the chunk's block write while a capture is rolled back: puts back what the capture cleared there. */
    public static void restoreStashed(LevelChunk chunk, BlockPos pos) {
        if (STASH.isEmpty() || chunk.getLevel().getGameTime() != stashTime) return;
        Long2ObjectOpenHashMap<CompoundTag> stashed = STASH.get(chunk);
        if (stashed == null) return;
        CompoundTag tag = stashed.remove(pos.asLong());
        if (tag != null) put(chunk, pos, tag);
    }

    /** Data moved here ahead of its block: the block's arrival this tick keeps it. */
    static void expectArrival(LevelChunk chunk, BlockPos pos) {
        DataBlocks blocks = of(chunk);
        if (blocks == null) return;
        if (blocks.arriving == null) blocks.arriving = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        blocks.arriving.put(pos.asLong(), chunk.getLevel().getGameTime());
    }

    /** From the chunk's block write: the block at the position was replaced by a different block. */
    public void forget(LevelChunk chunk, BlockPos pos) {
        long key = pos.asLong();
        if (arriving != null && arriving.containsKey(key)) {
            long when = arriving.remove(key);
            if (arriving.isEmpty()) arriving = null;
            if (when == chunk.getLevel().getGameTime()) return; // the moved block arriving: its data stays
        }
        CompoundTag removed = byPos.remove(key);
        if (removed == null) return;
        if (chunk.getLevel().captureBlockSnapshots) stash(chunk, key, removed);
        changed(chunk);
    }

    private static DataBlocks orCreate(LevelChunk chunk) {
        DataBlocks blocks = of(chunk);
        if (blocks == null) {
            blocks = new DataBlocks();
            chunk.setData(DataPort.BLOCKS.get(), blocks);
            ((DataChunk) chunk).hadron$setBlocks(blocks);
        }
        return blocks;
    }

    private void changed(LevelChunk chunk) {
        chunk.setUnsaved(true);
        if (byPos.isEmpty()) {
            chunk.removeData(DataPort.BLOCKS.get());
            ((DataChunk) chunk).hadron$setBlocks(null);
        }
    }
}
