package dev.iredsc.hadron.selftest.plugin;

import dev.iredsc.hadron.api.event.Event;

/** Not a test: {@link HostProbePlugin}'s own event, a class only that plugin's jar holds. */
public final class HostProbeEvent extends Event {
    public HostProbeEvent() {}
}
