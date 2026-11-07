package dev.iredsc.hadron.neoforge;

/**
 * A {@code LevelChunk}'s position data, as a field, so a block write in a chunk without any pays one null check.
 * Always the same object as the chunk's {@link DataPort#BLOCKS} attachment, or null when it has none.
 */
public interface DataChunk {
    DataBlocks hadron$blocks();

    void hadron$setBlocks(DataBlocks blocks);
}
