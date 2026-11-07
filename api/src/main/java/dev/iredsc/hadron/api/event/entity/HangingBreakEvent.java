package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.decoration.HangingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An item frame or painting is being broken and dropped. Cancel it and it stays where it hangs. Taking the item out
 * of a frame is not this; it is the frame's inventory changing.
 */
@FiredBy(core = FULL)
public class HangingBreakEvent extends EntityEvent implements Cancellable {
    /** Why it is breaking. */
    public enum RemoveCause {
        /** An entity hit it; the actor is that entity or whoever is behind it. */
        ENTITY,
        /** An explosion. */
        EXPLOSION,
        /** The block it hangs on is gone, or something now fills its space. */
        PHYSICS,
        /** It was pushed or moved, by a piston or anything else. */
        MOVED,
        /** Any other damage. */
        DEFAULT
    }

    private final RemoveCause cause;
    private boolean cancelled;

    public HangingBreakEvent(HangingEntity hanging, RemoveCause cause, Actor actor) {
        super(hanging, actor);
        this.cause = Objects.requireNonNull(cause, "cause; use RemoveCause.DEFAULT when no other fits");
    }

    @Override
    public HangingEntity getEntity() {
        return (HangingEntity) super.getEntity();
    }

    public RemoveCause getCause() {
        return cause;
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
