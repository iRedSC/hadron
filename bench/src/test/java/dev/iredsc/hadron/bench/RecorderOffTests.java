package dev.iredsc.hadron.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.Map;

/**
 * Benches time what a real server runs, so the harness's failure recorder (a snapshot and a trace line for every event
 * a listener hears) must stay off for this suite: the {@code META-INF/hadron-benchmarks} marker in its test resources
 * does that for every test here. This fails if the marker goes, or if the harness stops honouring it, before a bench
 * can report timings with the recorder inside them.
 */
public final class RecorderOffTests {
    /** An event a listener here hears, so a recorder that was on would record it. */
    public static final class Probe extends Event {}

    @HadronTest
    void benchesRunWithoutTheFailureRecorder(TestContext t) {
        int[] heard = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(Probe p) {
                heard[0]++;
            }
        });
        Hadron.getPluginManager().callEvent(new Probe());
        t.assertEquals(1, heard[0], "times the listener heard the probe");

        JsonObject root = JsonParser.parseString(plantedReport(t).get("json")).getAsJsonObject();
        JsonElement events = root.getAsJsonArray("suites").get(0).getAsJsonObject().getAsJsonArray("tests").get(0)
                .getAsJsonObject().getAsJsonObject("context").get("events");
        t.assertTrue(events.isJsonNull(), "A bench must run without the failure recorder, so its timings are a real "
                + "server's, but it recorded " + events + ". Put META-INF/hadron-benchmarks back in "
                + "bench/src/test/resources, and check TestRunner still reads it.");
    }

    /** The harness's report for a failure at this moment; see the selftest's FailureContextTests. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> plantedReport(TestContext t) {
        try {
            Class<?> recorder = Class.forName("dev.iredsc.hadron.harness.FailureRecorder", true,
                    t.getClass().getClassLoader());
            return (Map<String, String>) recorder.getMethod("plantedReport", TestContext.class, String.class)
                    .invoke(null, t, "planted failure");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Couldn't ask the harness for a planted report through "
                    + "FailureRecorder.plantedReport(TestContext, String): " + e, e);
        }
    }
}
