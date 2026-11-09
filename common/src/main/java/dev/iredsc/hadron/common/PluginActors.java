package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Operation;

/** Who is behind a plugin's own code. */
final class PluginActors {
    private PluginActors() {}

    /**
     * Runs a plugin's own code that no one asked for (a task, onEnable, onDisable) as {@link Actor.Server} named
     * {@code plugin <name>}, so what it changes is the server's doing, not nobody's: inside a watched area nobody's
     * changes are an outsider's, and protection would refuse a plugin resetting its own arena. Its own operation,
     * even over an actor already there: a player running /plugins enable doesn't own what the plugin's onEnable
     * builds, and a rollback of the player's command mustn't take it.
     */
    static void asPlugin(String plugin, Runnable work) {
        try (Actors.Scope ignored = Operation.begin(of(plugin))) {
            work.run();
        }
    }

    /** The plugin as an actor: the one name its tasks, lifecycle and handlers act under. */
    static Actor.Server of(String plugin) {
        return new Actor.Server("plugin " + plugin);
    }
}
