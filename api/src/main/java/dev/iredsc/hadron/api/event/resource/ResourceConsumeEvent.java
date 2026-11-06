package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;

/**
 * A holder's resource is about to be used up: a machine spending power, a spell's mana, a vein depleted. Cancel it
 * and the amount stays, and the action it paid for doesn't happen. Bridge event; see {@link ResourceEvent}.
 */
public class ResourceConsumeEvent extends ResourceChangeEvent {
    public ResourceConsumeEvent(Actor actor, Resource resource, ResourceHolder holder, long oldAmount, long newAmount) {
        super(actor, resource, holder, oldAmount, newAmount);
        if (newAmount >= oldAmount) {
            throw new IllegalArgumentException("Consuming " + resource.id() + " must lower the amount, but it goes "
                    + oldAmount + " -> " + newAmount + "; fire ResourceProduceEvent for an increase");
        }
    }
}
