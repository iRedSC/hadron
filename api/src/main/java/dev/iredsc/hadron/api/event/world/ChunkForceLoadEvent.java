package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * An actor is about to keep a chunk loaded with nobody near it: {@code /forceload}, a chunk loader, an anchor, a
 * claim. Cancel it and the chunk isn't forced (the loader's request fails, and nothing is saved). Short-lived
 * tickets (portals, teleports) don't fire this.
 */
public class ChunkForceLoadEvent extends WorldEvent implements Cancellable {
    private final ChunkPos chunkPos;
    private final boolean ticking;
    private boolean cancelled;

    public ChunkForceLoadEvent(Actor actor, ServerLevel level, ChunkPos chunkPos, boolean ticking) {
        super(actor, level, chunk(level, chunkPos));
        this.chunkPos = chunkPos;
        this.ticking = ticking;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    /** Whether the chunk will also tick (machines run, crops grow), not just stay loaded. */
    public boolean isTicking() {
        return ticking;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
