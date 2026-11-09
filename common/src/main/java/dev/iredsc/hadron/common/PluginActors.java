package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.plugin.JavaPlugin;

/** Who is behind a plugin's own code. */
final class PluginActors {
    private PluginActors() {}

    /**
     * Runs a plugin's own code that no one asked for (a task, onEnable, onDisable) as {@link Actor.Server} named
     * {@code plugin <name>}, so what it changes is the server's doing, not nobody's: inside a watched area nobody's
     * changes are an outsider's, and protection would refuse a plugin resetting its own arena. Pushed even over an
     * actor already there: a player running /plugins enable doesn't own what the plugin's onEnable builds.
     */
    static void asPlugin(JavaPlugin plugin, Runnable work) {
        try (Actors.Scope ignored = Actors.push(new Actor.Server("plugin " + plugin.getName()))) {
            work.run();
        }
    }
}
