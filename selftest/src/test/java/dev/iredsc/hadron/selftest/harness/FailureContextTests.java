package dev.iredsc.hadron.selftest.harness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * A failed test's report says what led up to the failure: the last events its listeners heard, as {@code /hadron
 * trace} shows them, and each player as the test ended, so an agent can diagnose it without rerunning with prints.
 * A test can't fail to read its own report, so these ask the harness for the report a failure at that moment would
 * get (a planted failure), through the same capture and the same report writer a real failure goes through.
 */
public final class FailureContextTests {
    @HadronTest
    void aFailuresReportHasTheEventsAndThePlayers(TestContext t) {
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                e.setCancelled(true);
            }
        });
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos dirt = t.pos(1, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        alice.breakBlock(dirt);
        t.assertBlock(dirt, Blocks.DIRT);
        // Changed after the break, so the report shows the player as the test ended, not as they joined.
        alice.player().setGameMode(GameType.ADVENTURE);
        String at = t.describe(alice.player().blockPosition());

        Map<String, String> report = plantedReport(t, "planted failure");
        String text = report.get("text");
        t.assertTrue(text.contains("planted failure"), "The planted failure should be in the report:\n" + text);
        t.assertTrue(text.contains("Events its listeners heard:"), "The report should list the events heard:\n" + text);
        t.assertTrue(text.contains("BlockBreakEvent · Player alice")
                        && text.contains("cancelled by " + t.plugin().getName()),
                "The report should show alice's break and who cancelled it, as /hadron trace does:\n" + text);
        t.assertTrue(text.contains("Players when it failed:"), "The report should list the players:\n" + text);
        t.assertTrue(text.contains("alice: adventure, in " + t.level().dimension().location() + " at " + at),
                "The report should give alice's game mode, world and position as the test ended:\n" + text);
        t.assertTrue(text.contains("holding 1 minecraft:diamond_pickaxe, off hand nothing"),
                "The report should give what alice held:\n" + text);
        t.assertTrue(text.strip().endsWith("RESULT: FAILED"), "The last line keeps its shape:\n" + text);

        JsonObject context = onlyTest(report).getAsJsonObject("context");
        t.assertEquals(0, context.get("eventsOmitted").getAsInt(), "events omitted from a short test");
        JsonObject breakEvent = context.getAsJsonArray("events").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> e.get("event").getAsString().equals("BlockBreakEvent")).findFirst()
                .orElseThrow(() -> new AssertionError("report.json's context should list the BlockBreakEvent: " + context));
        t.assertTrue(breakEvent.get("cancelled").getAsBoolean(), "report.json should say the break was cancelled: " + breakEvent);
        JsonArray players = context.getAsJsonArray("players");
        t.assertEquals(1, players.size(), "players in report.json's context: " + players);
        JsonObject p = players.get(0).getAsJsonObject();
        t.assertEquals("alice", p.get("name").getAsString(), "player name in report.json");
        t.assertTrue(p.get("online").getAsBoolean(), "alice is online: " + p);
        t.assertEquals("adventure", p.get("gameMode").getAsString(), "alice's game mode in report.json");
        t.assertEquals("1 minecraft:diamond_pickaxe", p.get("mainHand").getAsString(),
                "alice's main hand in report.json");
        t.assertEquals(at, p.get("pos").getAsString(), "alice's position in report.json");
    }

    /** A plugin's own event, fired many times to fill the report. */
    public static final class Ping extends Event {}

    @HadronTest
    void aLongTestsReportKeepsOnlyTheLatestEvents(TestContext t) {
        t.registerEvents(new Listener() {
            @EventHandler
            void on(Ping p) {}
        });
        int early = 20, late = 60;
        for (int i = 0; i < early; i++) Hadron.getPluginManager().callEvent(new Ping());
        t.tick(1);
        for (int i = 0; i < late; i++) Hadron.getPluginManager().callEvent(new Ping());

        Map<String, String> report = plantedReport(t, "planted failure");
        JsonObject context = onlyTest(report).getAsJsonObject("context");
        JsonArray events = context.getAsJsonArray("events");
        int omitted = context.get("eventsOmitted").getAsInt();
        t.assertTrue(events.size() > 0 && events.size() < late,
                "A long test's report should keep some events but not all " + late + " of the last tick; kept " + events.size());
        t.assertTrue(events.size() + omitted >= early + late,
                "Every event heard is either kept or counted as omitted; kept " + events.size() + ", omitted " + omitted);
        for (JsonElement e : events) {
            t.assertEquals(1, e.getAsJsonObject().get("tick").getAsInt(),
                    "The events kept should be the latest (tick 1), the earliest dropped: " + e);
        }
        t.assertTrue(report.get("text").contains("Events its listeners heard, the last " + events.size() + " ("
                        + omitted + " earlier events omitted):"),
                "The text report should say how many earlier events it left out:\n" + report.get("text"));
    }

    @HadronTest
    void anEventsTickIsWhenItFiredNotWhenItsHandlersReturned(TestContext t) {
        boolean[] waited = {false};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(Ping p) {
                // A handler that runs the server on before returning, as one waiting on a result would.
                if (!waited[0]) {
                    waited[0] = true;
                    t.tick(2);
                }
            }
        });
        Hadron.getPluginManager().callEvent(new Ping());
        t.assertEquals(2, t.ticksSpent(), "ticks the handler ran");

        JsonArray events = onlyTest(plantedReport(t, "planted failure")).getAsJsonObject("context")
                .getAsJsonArray("events");
        t.assertEquals(1, events.size(), "events heard: " + events);
        t.assertEquals(0, events.get(0).getAsJsonObject().get("tick").getAsInt(),
                "The event fired at tick 0, before its handler ran two ticks: " + events);
    }

    @HadronTest
    void aReportStaysValidJsonWhenAPlayersPositionIsntANumber(TestContext t) {
        TestPlayer alice = t.player("alice");
        net.minecraft.world.phys.Vec3 was = alice.player().position();
        Map<String, String> report;
        alice.player().setPosRaw(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        try {
            report = plantedReport(t, "planted failure");
        } finally {
            alice.player().setPosRaw(was.x, was.y, was.z);
        }
        JsonElement root;
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                new java.io.StringReader(report.get("json")))) {
            reader.setLenient(false);
            root = new com.google.gson.Gson().getAdapter(JsonElement.class).read(reader);
            t.assertEquals(com.google.gson.stream.JsonToken.END_DOCUMENT, reader.peek(), "what follows the report");
        } catch (java.io.IOException e) {
            throw new AssertionError("report.json isn't valid JSON, so a strict reader loses the whole report: " + e
                    + "\n" + report.get("json"), e);
        }
        JsonObject p = root.getAsJsonObject().getAsJsonArray("suites").get(0).getAsJsonObject()
                .getAsJsonArray("tests").get(0).getAsJsonObject().getAsJsonObject("context")
                .getAsJsonArray("players").get(0).getAsJsonObject();
        t.assertEquals("NaN", p.get("x").getAsString(), "a NaN coordinate in report.json");
        t.assertEquals("Infinity", p.get("y").getAsString(), "an infinite coordinate in report.json");
        t.assertEquals("-Infinity", p.get("z").getAsString(), "a negative infinite coordinate in report.json");
    }

    private static JsonObject onlyTest(Map<String, String> report) {
        JsonObject root = JsonParser.parseString(report.get("json")).getAsJsonObject();
        return root.getAsJsonArray("suites").get(0).getAsJsonObject().getAsJsonArray("tests").get(0).getAsJsonObject();
    }

    /** The harness's report for a failure at this moment: {@code text} and {@code json}. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> plantedReport(TestContext t, String message) {
        try {
            Class<?> recorder = Class.forName("dev.iredsc.hadron.harness.FailureRecorder", true,
                    t.getClass().getClassLoader());
            return (Map<String, String>) recorder.getMethod("plantedReport", TestContext.class, String.class)
                    .invoke(null, t, message);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Couldn't ask the harness for a planted report through "
                    + "FailureRecorder.plantedReport(TestContext, String): " + e, e);
        }
    }
}
