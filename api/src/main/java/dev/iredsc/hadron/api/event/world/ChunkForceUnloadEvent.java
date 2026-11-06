package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * A chunk forced by {@link ChunkForceLoadEvent} has been released, by whoever forced it or because its loader was
 * removed. After the fact: not cancellable, so a loader can always let go.
 */
public class ChunkForceUnloadEvent extends WorldEvent {
    private final ChunkPos chunkPos;

    public ChunkForceUnloadEvent(Actor actor, ServerLevel level, ChunkPos chunkPos) {
        super(actor, level, chunk(level, chunkPos));
        this.chunkPos = chunkPos;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }
}
