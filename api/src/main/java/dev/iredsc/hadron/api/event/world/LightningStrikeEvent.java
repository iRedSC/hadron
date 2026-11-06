package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;

/**
 * Lightning is about to strike: a storm, a channeling trident (the thrower is the actor), a command or a mod. Cancel
 * it and the bolt never appears, so it starts no fire and hurts nothing.
 */
public class LightningStrikeEvent extends WorldEvent implements Cancellable {
    private final LightningBolt lightning;
    private boolean cancelled;

    public LightningStrikeEvent(Actor actor, ServerLevel level, LightningBolt lightning) {
        super(actor, level, around(lightning.position(), 4));
        this.lightning = lightning;
    }

    public LightningBolt getLightning() {
        return lightning;
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
