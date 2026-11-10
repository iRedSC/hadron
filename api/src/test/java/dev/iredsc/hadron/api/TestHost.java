package dev.iredsc.hadron.api;

/**
 * The api tests' stand-in for Hadron's mod: the one {@link HostKey} in this test JVM, claimed the first time a test
 * needs it. In game, Hadron's mod claims it as it loads, so a plugin's tests can never claim it.
 */
public final class TestHost {
    public static final HostKey KEY = HostKey.claim();

    private TestHost() {}
}
