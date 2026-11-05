package dev.iredsc.hadron.examples.hello;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

public final class HelloPlugin extends JavaPlugin {
    static final String GREETING = "Hello";

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskLater(this, () -> getLogger().info("A tick later: {} is running", getName()), 1);
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving, {}", getServer().isStopping() ? "server stopping" : "reloading");
    }

    @SubscribeEvent
    private void started(ServerStartedEvent event) {
        getLogger().info("Heard ServerStartedEvent");
    }

    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        String who = args.length > 0 ? args[0] : sender.getTextName();
        sender.sendSuccess(() -> Component.literal(GREETING + ", " + who + " (via /" + label + ")"), false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        return List.of("world", "everyone");
    }
}
