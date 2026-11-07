package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A chunk has loaded and is in the world. Not cancellable: a chunk something needs can't be refused. */
@FiredBy(core = FULL)
public class ChunkLoadEvent extends WorldEvent {
    private final LevelChunk chunk;
    private final boolean newChunk;

    public ChunkLoadEvent(Actor actor, ServerLevel level, LevelChunk chunk, boolean newChunk) {
        super(actor, level, chunk(level, chunk.getPos()));
        this.chunk = chunk;
        this.newChunk = newChunk;
    }

    public LevelChunk getChunk() {
        return chunk;
    }

    public ChunkPos getChunkPos() {
        return chunk.getPos();
    }

    /** Whether it was generated just now rather than read from disk. */
    public boolean isNewChunk() {
        return newChunk;
    }
}
