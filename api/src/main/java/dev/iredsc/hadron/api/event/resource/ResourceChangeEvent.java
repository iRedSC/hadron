package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;

/**
 * One holder's amount of a resource is about to change from {@link #getOldAmount()} to {@link #getNewAmount()}.
 * Fire the leaf that says why when one fits: {@link ResourceProduceEvent} (made from nothing),
 * {@link ResourceConsumeEvent} (used up) or {@link ResourceConvertEvent} (turned into another resource); fire this
 * one only when none does, such as an amount set by a command. Moving between holders is
 * {@link ResourceTransferEvent}. Bridge event; see {@link ResourceEvent}.
 */
public class ResourceChangeEvent extends ResourceEvent {
    private final ResourceHolder holder;
    private final long oldAmount;
    private final long newAmount;

    public ResourceChangeEvent(Actor actor, Resource resource, ResourceHolder holder, long oldAmount, long newAmount) {
        super(actor, resource, Math.abs(newAmount - oldAmount));
        this.holder = checkHolder(resource, holder, "holder");
        this.oldAmount = oldAmount;
        this.newAmount = newAmount;
    }

    public ResourceHolder getHolder() {
        return holder;
    }

    public long getOldAmount() {
        return oldAmount;
    }

    public long getNewAmount() {
        return newAmount;
    }

    @Override
    public List<ResourceHolder> getHolders() {
        return List.of(holder);
    }
}
