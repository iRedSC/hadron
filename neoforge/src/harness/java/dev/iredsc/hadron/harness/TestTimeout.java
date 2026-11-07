package dev.iredsc.hadron.harness;

/** A test spent all the ticks its {@code @HadronTest(timeout)} allows. Thrown from the tick that would pass it. */
final class TestTimeout extends AssertionError {
    TestTimeout(String test, int timeout) {
        super(test + " ran out of ticks: it has spent all " + timeout + " its @HadronTest allows. If it needs more, "
                + "raise the limit with @HadronTest(timeout = " + timeout * 2 + "); if it shouldn't, what it was "
                + "waiting for never happened (the line below is where it was).");
    }
}
