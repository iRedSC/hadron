package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.MinecraftServer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** Once per server start, after every plugin has enabled and the worlds are loaded. Never on a plugin reload. */
@FiredBy(core = FULL)
public class ServerLoadEvent extends ServerEvent {
    private final MinecraftServer server;

    public ServerLoadEvent(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
