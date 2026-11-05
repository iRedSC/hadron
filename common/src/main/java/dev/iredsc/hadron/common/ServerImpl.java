package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.api.plugin.Scheduler;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.ServicesManager;
import java.io.File;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ServerImpl implements Server {
    private final PluginHost host;
    private final MinecraftServer server;
    private final File pluginsFolder;
    private final File configFolder;

    ServerImpl(PluginHost host, MinecraftServer server, File pluginsFolder, File configFolder) {
        this.host = host;
        this.server = server;
        this.pluginsFolder = pluginsFolder;
        this.configFolder = configFolder;
    }

    @Override
    public PluginManager getPluginManager() {
        return host;
    }

    @Override
    public Scheduler getScheduler() {
        return host.scheduler();
    }

    @Override
    public ServicesManager getServicesManager() {
        return host.services();
    }

    @Override
    public MinecraftServer getMinecraftServer() {
        return server;
    }

    @Override
    public List<ServerPlayer> getOnlinePlayers() {
        return List.copyOf(server.getPlayerList().getPlayers());
    }

    @Override
    public ServerPlayer getPlayer(String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    @Override
    public ServerPlayer getPlayer(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    @Override
    public void broadcastMessage(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(Colors.translate(message)), false);
    }

    @Override
    public boolean dispatchCommand(CommandSourceStack sender, String commandLine) {
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String name = line.split(" ", 2)[0];
        if (server.getCommands().getDispatcher().getRoot().getChild(name) == null) return false;
        server.getCommands().performPrefixedCommand(sender, line);
        return true;
    }

    @Override
    public CommandSourceStack getConsoleSender() {
        return server.createCommandSourceStack();
    }

    @Override
    public boolean isStopping() {
        return host.stopping();
    }

    @Override
    public File getPluginsFolder() {
        return pluginsFolder;
    }

    @Override
    public File getConfigFolder() {
        return configFolder;
    }
}
