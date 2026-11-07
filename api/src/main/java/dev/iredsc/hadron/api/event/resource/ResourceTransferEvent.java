package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;

/**
 * An amount is about to move from one holder to another: a cable, a pipe, a wireless charger reaching a player in
 * another dimension, a payment between accounts. Fired once for the real move, never for the simulation a pipe runs
 * first. Cancel it and both holders keep what they had. Bridge event; see {@link ResourceEvent}.
 */
public class ResourceTransferEvent extends ResourceEvent {
    private final ResourceHolder source;
    private final ResourceHolder destination;

    public ResourceTransferEvent(Actor actor, Resource resource, ResourceHolder source, ResourceHolder destination,
            long amount) {
        super(actor, resource, amount);
        this.source = checkHolder(resource, source, "source");
        this.destination = checkHolder(resource, destination, "destination");
    }

    /** Where it comes from. */
    public ResourceHolder getSource() {
        return source;
    }

    /** Where it goes. */
    public ResourceHolder getDestination() {
        return destination;
    }

    @Override
    public List<ResourceHolder> getHolders() {
        return List.of(source, destination);
    }
}
