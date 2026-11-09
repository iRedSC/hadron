package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Operation;
import org.junit.jupiter.api.Test;

class PluginActorsTest {
    @Test
    void aPluginsOwnWorkIsItsOwnOperationEvenInsideAPlayersCommand() {
        Actor alice = new Actor.Server("alice's command"); // any actor already acting, as a player's command would be
        try (Actors.Scope ignored = Operation.begin(alice)) {
            Operation command = Operation.current();
            assertNotNull(command);
            Operation[] inside = new Operation[1];
            Actor[] actor = new Actor[1];
            PluginActors.asPlugin("Arena", () -> {
                inside[0] = Operation.current();
                actor[0] = Actors.current().orElse(null);
            });
            assertEquals(new Actor.Server("plugin Arena"), actor[0], "who the plugin's own work acts as");
            assertNotNull(inside[0]);
            assertNotEquals(command.id(), inside[0].id(), "the plugin's work shouldn't join the command's operation, "
                    + "or rolling back the command would undo it");
            assertEquals(command, Operation.current(), "the command's operation is back once the plugin's work ends");
        }
    }
}
