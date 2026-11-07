package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A holder's resource is about to be used up in one step: a spell's mana, a launch's fuel, a vein depleted. Cancel it
 * and the amount stays, and the action it paid for doesn't happen. A machine's running draw during a job isn't
 * reported: its job's own event is. Who fires it: see {@link ResourceEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "currency withdrawals",
        bridges = "energy, fluid, chemical, mana, heat, radiation, a vein's reserve or skill points the mod uses up in "
                + "one step, never a running machine's per-tick draw, with resource id and unit, holder, old and new "
                + "amount")
public class ResourceConsumeEvent extends ResourceChangeEvent {
    public ResourceConsumeEvent(Actor actor, Resource resource, ResourceHolder holder, long oldAmount, long newAmount) {
        super(actor, resource, holder, oldAmount, newAmount);
        if (newAmount >= oldAmount) {
            throw new IllegalArgumentException("Consuming " + resource.id() + " must lower the amount, but it goes "
                    + oldAmount + " -> " + newAmount + "; fire ResourceProduceEvent for an increase");
        }
    }
}
