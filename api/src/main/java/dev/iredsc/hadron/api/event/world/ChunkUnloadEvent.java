package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A chunk is unloading. Not cancellable; to keep chunks loaded, force them (see {@link ChunkForceLoadEvent}). */
@FiredBy(core = FULL)
public class ChunkUnloadEvent extends WorldEvent {
    private final LevelChunk chunk;

    public ChunkUnloadEvent(Actor actor, ServerLevel level, LevelChunk chunk) {
        super(actor, level, chunk(level, chunk.getPos()));
        this.chunk = chunk;
    }

    public LevelChunk getChunk() {
        return chunk;
    }

    public ChunkPos getChunkPos() {
        return chunk.getPos();
    }
}
