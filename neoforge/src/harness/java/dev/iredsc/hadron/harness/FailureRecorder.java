package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.common.PluginHost;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Records, while one test runs, what its report would need if it failed: every event its listeners hear, as
 * {@code /hadron trace} shows it, keeping the last {@link RecentEvents#KEPT}. It hears only events some listener
 * handles, so no chokepoint builds an event for it alone, and a passing test pays a line of text per event heard.
 * {@link #capture} adds the players as they are, and is called only for a test that didn't pass.
 */
public final class FailureRecorder {
    private final TestWorld world;
    private final RecentEvents events = new RecentEvents();
    private PluginHost.@Nullable EventRecording recording;

    FailureRecorder(TestWorld world) {
        this.world = world;
    }

    /** Whether {@link #start} began recording, so a report can tell "none heard" from "not recorded". */
    private boolean started;

    /** From the test's first line; on the server thread. Not called for a benchmark suite's tests. */
    void start() {
        PluginHost host = Hooks.host();
        if (host == null || recording != null) return;
        recording = host.recordEvents(world::ticksSpent, this::heard);
        started = true;
    }

    /** Before cleanup, so the test's own disconnects and world removal aren't recorded. */
    void stop() {
        PluginHost.EventRecording r = recording;
        recording = null;
        if (r != null) r.close();
    }

    /** {@code firedAt} is the test's tick as the event fired, not as its last handler returned. */
    private void heard(Event event, int firedAt, String line) {
        Boolean cancelled = event instanceof Cancellable c ? c.isCancelled() : null;
        events.add(firedAt, event.getEventName(), cancelled, line);
    }

    /**
     * The events so far and every player as they are now. The watchdog calls this off the server thread for a hung
     * test, whose server thread is stuck; a player it can't read is reported as such rather than lose the report.
     */
    FailureContext capture() {
        RecentEvents.Tail tail = events.tail();
        List<FailureContext.PlayerState> players = new ArrayList<>();
        for (TestPlayer p : world.players()) {
            FailureContext.Online online;
            try {
                online = online(p);
            } catch (RuntimeException e) {
                online = null;
            }
            players.add(new FailureContext.PlayerState(p.name(), online));
        }
        return started ? new FailureContext(tail.events(), tail.omitted(), players)
                : new FailureContext(null, 0, players);
    }

    private FailureContext.@Nullable Online online(TestPlayer p) {
        // The live player, not TestPlayer.player(): after a respawn that is still the old, dead one.
        ServerPlayer player = world.server().getPlayerList().getPlayer(p.uuid());
        if (player == null) return null;
        boolean here = player.serverLevel() == world.level();
        return new FailureContext.Online(player.serverLevel().dimension().location().toString(),
                here ? world.describe(player.blockPosition()) : null, player.getX(), player.getY(), player.getZ(),
                player.gameMode.getGameModeForPlayer().getName(), item(player.getMainHandItem()),
                item(player.getOffhandItem()));
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    /**
     * For the harness's self-tests, which can't fail to see their own report: the report this test would get if it
     * failed now with {@code message}, as {@code text} (report.txt) and {@code json} (report.json). Reached by
     * reflection, since test-api doesn't offer it to plugins.
     */
    public static Map<String, String> plantedReport(TestContext t, String message) {
        if (!(t instanceof TestWorld world)) {
            throw new IllegalArgumentException("plantedReport takes the TestContext the harness passed the test");
        }
        TestReport report = new TestReport();
        TestReport.Suite suite = report.suite("planted.jar", world.plugin().getName());
        report.add(suite, new TestReport.Result("Planted.failure", "Planted", "failure", TestReport.Status.FAIL, null,
                null, message, null, world.ticksSpent(), 0, world.failures.capture()));
        return Map.of("text", report.render(), "json", report.json());
    }
}
