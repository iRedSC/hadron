package dev.iredsc.hadron.selftest.harness;

import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link TestContext#awaitAsync}: a wait on another thread that gives it wall time between ticks, still bounded by
 * ticks. examples/hello uses it from a plugin's build, where no exemption is allowed.
 */
public final class AwaitAsyncTests {
    /** Stands in for slow work on another thread: done after {@code millis} of wall time, whatever the ticks do. */
    @Nondeterministic(reason = "plays work on another thread that takes wall time, which is what awaitAsync waits for")
    private static AtomicBoolean doneAfter(long millis) {
        AtomicBoolean done = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.set(true);
        }, "selftest-slow-worker");
        worker.setDaemon(true);
        worker.start();
        return done;
    }

    @HadronTest
    void waitsForWorkOnAnotherThreadThatTicksAloneOutrun(TestContext t) {
        // 40 ms of wall time; 30 ticks with nothing to do run in a few, so only the time given between them gets there.
        AtomicBoolean done = doneAfter(40);
        int before = t.ticksSpent();
        t.awaitAsync("the slow worker to finish", done::get, 30);
        t.assertTrue(done.get(), "awaitAsync returned before the worker finished");
        t.assertTrue(t.ticksSpent() - before < 30, "awaitAsync should return once the worker is done, not spend all "
                + "30 ticks; it spent " + (t.ticksSpent() - before));
    }

    @HadronTest
    void returnsAtOnceWhenTheConditionAlreadyHolds(TestContext t) {
        int before = t.ticksSpent();
        t.awaitAsync("something already true", () -> true, 10);
        t.assertEquals(before, t.ticksSpent(), "ticks spent waiting for a condition that already held");
    }

    @HadronTest
    void failsNamingWhatItWaitedForAfterItsTicks(TestContext t) {
        int before = t.ticksSpent();
        AssertionError failure = null;
        try {
            t.awaitAsync("the flag nobody sets", () -> false, 5);
        } catch (AssertionError e) {
            failure = e;
        }
        t.assertTrue(failure != null, "awaitAsync should fail when the condition never holds");
        String message = String.valueOf(failure.getMessage());
        t.assertTrue(message.contains("the flag nobody sets") && message.contains("raise the ticks given to awaitAsync")
                && message.contains("server log"), "the failure should name what it waited for and say what to "
                + "check, but was: " + message);
        t.assertEquals(5, t.ticksSpent() - before, "ticks spent before giving up");
    }
}
