package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A chunk forced by {@link ChunkForceLoadEvent} has been released, by whoever forced it or because its loader was
 * removed. After the fact: not cancellable, so a loader can always let go.
 */
@FiredBy(core = FULL)
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
