package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerLevel;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A world (a dimension) has just been saved. After the fact: not cancellable. */
@FiredBy(core = FULL)
public class WorldSaveEvent extends WorldEvent {
    public WorldSaveEvent(ServerLevel level) {
        super(Actor.Nature.INSTANCE, level, null);
    }
}
