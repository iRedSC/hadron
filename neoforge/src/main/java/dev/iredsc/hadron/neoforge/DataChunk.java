package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;

/**
 * A {@code LevelChunk}'s position data, as a field, so a block write in a chunk without any pays one null check.
 * Always the same object as the chunk's {@link DataPort#BLOCKS} attachment, or null when it has none.
 */
public interface DataChunk {
    @Nullable DataBlocks hadron$blocks();

    void hadron$setBlocks(@Nullable DataBlocks blocks);
}
