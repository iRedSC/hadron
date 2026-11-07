package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * One holder's amount of a resource is about to change from {@link #getOldAmount()} to {@link #getNewAmount()}.
 * Fire the leaf that says why when one fits: {@link ResourceProduceEvent} (made from nothing),
 * {@link ResourceConsumeEvent} (used up) or {@link ResourceConvertEvent} (turned into another resource); fire this
 * one only when none does, such as an amount set by a command. Moving between holders is
 * {@link ResourceTransferEvent}. Who fires it: see {@link ResourceEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "currencies, through its produce and consume leaves",
        bridges = "an amount of energy, fluid in a tank, chemical, mana, heat, radiation, a vein's reserve or skill "
                + "points that changes with no fitting leaf (set by a command), with resource id and unit, holder, "
                + "old and new amount")
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
