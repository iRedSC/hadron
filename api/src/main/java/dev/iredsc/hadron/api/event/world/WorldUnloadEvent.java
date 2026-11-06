package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.server.level.ServerLevel;

/** A world (a dimension) is unloading; it is still readable while this runs. After the fact: not cancellable. */
public class WorldUnloadEvent extends WorldEvent {
    public WorldUnloadEvent(ServerLevel level) {
        super(Actor.Nature.INSTANCE, level, null);
    }
}
