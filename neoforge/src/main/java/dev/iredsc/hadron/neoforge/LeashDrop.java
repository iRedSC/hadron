package dev.iredsc.hadron.neoforge;

/**
 * Why a lead is about to come off, when the code dropping it knows better than the entity's state can say. Set on the
 * entity only while its {@code dropLeash} runs.
 */
public enum LeashDrop {
    /** Nothing said: judge the reason from the state. */
    UNSAID,
    /** A player took it off, already asked as an {@code EntityUnleashEvent}: no break to report. */
    CHOSEN,
    /** The entity is on its way out of the world (a boat broken, a trader's llama despawning). */
    LEAVING
}
