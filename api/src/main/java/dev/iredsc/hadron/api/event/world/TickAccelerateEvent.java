package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * An actor is about to speed up blocks or entities in an area: a Torcherino, a time bottle, an accelerator. Fires once
 * per accelerator per change (started, sped up, area moved), never per extra tick. Cancel it and the area runs at its
 * old speed.
 *
 * <p><b>Bridge event.</b> Vanilla has no acceleration, so Hadron core never fires this; the bridge for a mod that
 * accelerates fires it before the change and drops the change if cancelled, filling in the area and the new speed.
 */
public class TickAccelerateEvent extends WorldEvent implements Cancellable {
    private final double multiplier;
    private boolean cancelled;

    /**
     * @param multiplier how many times normal speed the area will run, such as 4 for four times as fast; 1 when the
     *     accelerator stops
     */
    public TickAccelerateEvent(Actor actor, ServerLevel level, BoundingBox area, double multiplier) {
        super(actor, level, Objects.requireNonNull(area, "area; a tick accelerator always covers an area"));
        if (!(multiplier >= 0) || Double.isInfinite(multiplier)) {
            throw new IllegalArgumentException("Tick multiplier must be a finite number of at least 0, got "
                    + multiplier + "; pass 1 when the accelerator stops");
        }
        this.multiplier = multiplier;
    }

    /** How many times normal speed the area will run; 1 means back to normal. */
    public double getMultiplier() {
        return multiplier;
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
