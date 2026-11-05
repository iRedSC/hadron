package dev.iredsc.hadron.examples.hello;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;

public final class HelloPlugin extends JavaPlugin implements Listener {
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

    /** Nobody says hello to "nobody". Logs which version heard it, so a reload that left an old handler shows. */
    @EventHandler
    private void hello(HelloEvent event) {
        getLogger().info("{} heard HelloEvent for {}", GREETING, event.getWho());
        if (event.getWho().equals("nobody")) event.setCancelled(true);
    }

    @EventHandler
    private void loaded(ServerLoadEvent event) {
        getLogger().info("Heard ServerLoadEvent");
    }

    /** Messages containing "secret" are never sent; "hello" gets answered in the same breath. */
    @EventHandler(ignoreCancelled = true)
    private void chat(PlayerChatEvent event) {
        if (event.getRawText().contains("secret")) event.setCancelled(true);
        else if (event.getRawText().contains("hello")) event.setMessage(Component.literal(event.getRawText() + " (hi!)"));
    }

    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        String who = args.length > 0 ? args[0] : sender.getTextName();
        if (getServer().getPluginManager().callEvent(new HelloEvent(who))) {
            sender.sendFailure(Component.literal("Not greeting " + who));
            return true;
        }
        sender.sendSuccess(() -> Component.literal(GREETING + ", " + who + " (via /" + label + ")"), false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        return List.of("world", "everyone");
    }
}
