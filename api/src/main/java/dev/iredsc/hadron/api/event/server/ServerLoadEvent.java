package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.Event;
import net.minecraft.server.MinecraftServer;

/** Once per server start, after every plugin has enabled and the worlds are loaded. Never on a plugin reload. */
public class ServerLoadEvent extends Event {
    private final MinecraftServer server;

    public ServerLoadEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
