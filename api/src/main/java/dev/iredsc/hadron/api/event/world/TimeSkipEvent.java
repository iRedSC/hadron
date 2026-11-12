package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerLevel;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * The time of day is about to jump: everyone slept through the night ({@link Actor.Nature}), a command, or a mod.
 * Never fires for the clock's ordinary tick. Change how far it jumps, or cancel to leave the time alone.
 */
@FiredBy(core = FULL)
public class TimeSkipEvent extends WorldEvent implements Cancellable {
    private final long oldTime;
    private long skipAmount;
    private boolean cancelled;

    public TimeSkipEvent(Actor actor, ServerLevel level, long oldTime, long skipAmount) {
        super(actor, level, null);
        this.oldTime = oldTime;
        this.skipAmount = skipAmount;
    }

    /** The day time before the jump, in ticks since the world began. */
    public long getOldTime() {
        return oldTime;
    }

    /** The day time after the jump. */
    public long getNewTime() {
        return oldTime + skipAmount;
    }

    /** How many ticks it jumps; negative when the time is set back. */
    public long getSkipAmount() {
        return skipAmount;
    }

    public void setSkipAmount(long skipAmount) {
        this.skipAmount = skipAmount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Every level that sees the change: this one, and each level whose data wraps this one's (vanilla's Nether and
     * End share the overworld's weather and time), one {@link Place.Level} each.
     */
    @Override
    public Places getPlaces() {
        return SharedData.levels(getLevel());
    }
}
