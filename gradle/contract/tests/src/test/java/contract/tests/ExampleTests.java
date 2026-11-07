package contract.tests;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;

/**
 * An example of the in-game tier: run by {@code ./gradlew hadronTest} in a headless server with real players. Use it for
 * anything that touches the world, a player or a packet. Each test gets its own world and players.
 */
public final class ExampleTests {
    @HadronTest
    @Covers(PlayerJoinEvent.class)
    void aJoiningPlayerFiresPlayerJoinEvent(TestContext t) {
        List<String> joined = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void onJoin(PlayerJoinEvent event) {
                joined.add(event.getPlayer().getGameProfile().getName());
            }
        });
        TestPlayer alice = t.player("alice");
        t.assertEquals(List.of("alice"), joined, "the players PlayerJoinEvent fired for");
        t.assertTrue(alice.isConnected(), "alice should still be connected after joining");
    }
}
