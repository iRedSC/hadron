package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A resource is about to be made from nothing into a holder: a generator's power, a reactor's waste, a quest's
 * currency reward. Cancel it and nothing is made, and the fuel or cost behind it is refunded. Who fires it: see
 * {@link ResourceEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "currency deposits",
        bridges = "energy, fluid, chemical, mana, heat, radiation or skill points the mod makes from nothing, with "
                + "resource id and unit, holder, old and new amount")
public class ResourceProduceEvent extends ResourceChangeEvent {
    public ResourceProduceEvent(Actor actor, Resource resource, ResourceHolder holder, long oldAmount, long newAmount) {
        super(actor, resource, holder, oldAmount, newAmount);
        if (newAmount <= oldAmount) {
            throw new IllegalArgumentException("Producing " + resource.id() + " must raise the amount, but it goes "
                    + oldAmount + " -> " + newAmount + "; fire ResourceConsumeEvent for a decrease");
        }
    }
}
