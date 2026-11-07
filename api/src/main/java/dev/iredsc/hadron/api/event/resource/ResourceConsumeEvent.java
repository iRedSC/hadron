package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * A holder's resource is about to be used up: a machine spending power, a spell's mana, a vein depleted. Cancel it
 * and the amount stays, and the action it paid for doesn't happen. Who fires it: see {@link ResourceEvent}.
 */
@FiredBy(core = PARTIAL,
        coreSees = "currency withdrawals",
        bridges = "energy, fluid, chemical, mana, heat, radiation, a vein's reserve or skill points the mod uses up, "
                + "with resource id and unit, holder, old and new amount")
public class ResourceConsumeEvent extends ResourceChangeEvent {
    public ResourceConsumeEvent(Actor actor, Resource resource, ResourceHolder holder, long oldAmount, long newAmount) {
        super(actor, resource, holder, oldAmount, newAmount);
        if (newAmount >= oldAmount) {
            throw new IllegalArgumentException("Consuming " + resource.id() + " must lower the amount, but it goes "
                    + oldAmount + " -> " + newAmount + "; fire ResourceProduceEvent for an increase");
        }
    }
}
