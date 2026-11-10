package dev.iredsc.hadron.api.event;

/**
 * An event that says where it happens. Every action, every player event, and every server event with a target or an
 * area is one, so a protection plugin can check each place against its regions without knowing which event it is.
 */
public interface Placed {
    /**
     * Every place this happens, never empty: the union of the place of each part of its payload (a move's source and
     * destination, each inventory a change touches, each entity it names), in the order its getters name them. Read
     * from the event's own getters on every call, so it never disagrees with them: a listener that redirects a teleport
     * or spares a block from an explosion changes the places with it.
     *
     * <p>Where core can't know where something happens, its place is {@link Place.Unknown}, with why and a bound; a
     * guessed spot is a bug. Where it is known to have no place in a world, it is {@link Place.Nowhere}. The actor's
     * own position is never one of these places: {@code getActor()} already says where the actor is.
     */
    Places getPlaces();
}
