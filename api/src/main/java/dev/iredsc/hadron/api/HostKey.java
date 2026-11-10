package dev.iredsc.hadron.api;

import dev.iredsc.hadron.checks.StaticState;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Proof that the caller is Hadron's plugin host or loader port. Plugins never use it.
 *
 * <p>Everything that replaces or clears what the host installed (the running {@link Hadron#getServer() server}, the
 * economy, plugins' permission nodes, the port's backends) takes this key and refuses a call without it. There is one
 * key per JVM, and Hadron's mod {@link #claim claims} it as it loads, before any plugin can run, so no plugin can hold
 * it by mistake: not by calling {@link #claim} (it throws once the key is taken), not by passing null, not by making
 * one. A plugin's build refuses any use of it before that (the {@code HostOnly} check). Reflection on the private
 * field gets past it, as it gets past anything in one JVM; plugins are trusted code, so that is out of scope
 * (docs/design.md, "Mistakes, not malice").
 */
public final class HostKey {
    @StaticState(reason = "the JVM's one key: claimed once by Hadron's mod as it loads, before any plugin, and never "
            + "cleared, since Hadron itself is never reloaded")
    private static final AtomicReference<@Nullable HostKey> issued = new AtomicReference<>();

    private HostKey() {}

    /**
     * The key, to the first caller only: Hadron's mod as it loads, or a test of Hadron's own that stands in for it.
     *
     * @throws IllegalStateException on every later call.
     */
    public static HostKey claim() {
        HostKey key = new HostKey();
        if (!issued.compareAndSet(null, key)) {
            throw new IllegalStateException("HostKey.claim() is for Hadron's own mod, which already holds the key; "
                    + "a plugin can't replace or clear what Hadron installed. Read it instead: "
                    + "Hadron.getServer(), Economy.get(), Node.declare, Packets, Worlds or Areas. A test gets a "
                    + "running server from @HadronTest.");
        }
        return key;
    }

    /**
     * Refuses {@code member} unless {@code key} is the one {@link #claim} handed out.
     *
     * @param member what was called, such as {@code "Hadron.Lifecycle.install"}.
     * @param instead what a plugin uses for the same need, such as {@code "read the running server with
     *     Hadron.getServer()"}.
     * @throws IllegalStateException without the key, saying what to use instead.
     */
    public static void require(@Nullable HostKey key, String member, String instead) {
        if (key == null || key != issued.get()) {
            throw new IllegalStateException(member + " is for Hadron's own host, which holds the only HostKey it "
                    + "takes, so a plugin can't replace or clear what Hadron installed. Instead, " + instead + ".");
        }
    }
}
