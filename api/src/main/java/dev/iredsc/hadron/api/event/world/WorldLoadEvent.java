package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.server.level.ServerLevel;

/** A world (a dimension) has loaded, before players enter it. After the fact: not cancellable. */
public class WorldLoadEvent extends WorldEvent {
    public WorldLoadEvent(ServerLevel level) {
        super(Actor.Nature.INSTANCE, level, null);
    }
}
