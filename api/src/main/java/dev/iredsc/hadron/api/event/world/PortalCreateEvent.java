package dev.iredsc.hadron.api.event.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A nether portal is about to be made. Cancel it and no portal block appears: a lit frame stays a frame (the fire
 * that lit it remains), and someone travelling to a world with no portal yet doesn't travel.
 */
@FiredBy(core = FULL)
public class PortalCreateEvent extends WorldEvent implements Cancellable {
    public enum CreateReason {
        /** A frame was lit: by fire, or a mod filling a frame. {@link #getArea()} is exactly the portal blocks. */
        FIRE,
        /**
         * Someone went through a portal and the other world needs an exit. The spot isn't chosen yet, so
         * {@link #getArea()} is the area it will be built in.
         */
        NETHER_PAIR
    }

    private final CreateReason reason;
    private boolean cancelled;

    public PortalCreateEvent(Actor actor, ServerLevel level, BoundingBox area, CreateReason reason) {
        super(actor, level, Objects.requireNonNull(area, "area"));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public CreateReason getReason() {
        return reason;
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
