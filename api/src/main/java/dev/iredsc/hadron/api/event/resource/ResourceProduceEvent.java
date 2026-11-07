package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A resource is about to be made from nothing into a holder in one step: a reactor's waste, a spell conjuring water,
 * a quest's currency reward. A generator's per-tick output during a job isn't reported. Cancel it and
 * nothing is made, and the fuel or cost behind it is refunded. Who fires it: see {@link ResourceEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "currency deposits",
        bridges = "energy, fluid, chemical, mana, heat, radiation or skill points the mod makes from nothing in one "
                + "step, never a running generator's per-tick output, with resource id and unit, holder, old and new "
                + "amount")
public class ResourceProduceEvent extends ResourceChangeEvent {
    public ResourceProduceEvent(Actor actor, Resource resource, ResourceHolder holder, long oldAmount, long newAmount) {
        super(actor, resource, holder, oldAmount, newAmount);
        if (newAmount <= oldAmount) {
            throw new IllegalArgumentException("Producing " + resource.id() + " must raise the amount, but it goes "
                    + oldAmount + " -> " + newAmount + "; fire ResourceConsumeEvent for a decrease");
        }
    }
}
