package dev.iredsc.hadron.selftest.hostile;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;

/** Not a test: {@link HostilePlugin}'s listener, which throws on every event Hadron fires. */
public final class HostileListener implements Listener {
    @EventHandler
    public void on(Event event) {
        throw new IllegalStateException("hostile listener on " + event.getClass().getSimpleName());
    }
}
