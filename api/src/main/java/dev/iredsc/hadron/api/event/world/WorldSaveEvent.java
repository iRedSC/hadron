package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.server.level.ServerLevel;

/** A world (a dimension) has just been saved. After the fact: not cancellable. */
public class WorldSaveEvent extends WorldEvent {
    public WorldSaveEvent(ServerLevel level) {
        super(Actor.Nature.INSTANCE, level, null);
    }
}
