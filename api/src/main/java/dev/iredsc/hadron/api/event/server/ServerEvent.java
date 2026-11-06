package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.Event;

/** Something about the server as a whole: its start, commands, plugins, services, claims. Listen for this to hear all. */
public abstract class ServerEvent extends Event {
    protected ServerEvent() {}
}
