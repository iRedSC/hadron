package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;

/** What the world mixins add to every {@code ServerPlayer}. */
public interface PlayerState {
    /** This player as an actor, made once, so attributing each of their packets allocates no actor. */
    Actor.Player hadron$actor();

    /** Records the abilities and level the client has just been told, so later changes are measured from them. */
    void hadron$syncAbilities();
}
