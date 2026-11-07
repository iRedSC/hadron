package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.event.Event;

/** A plugin's own event class. {@link TestPlugins} loads a fresh copy per plugin, as each plugin version has its own. */
public final class StubEvent extends Event {
    public StubEvent() {}
}
