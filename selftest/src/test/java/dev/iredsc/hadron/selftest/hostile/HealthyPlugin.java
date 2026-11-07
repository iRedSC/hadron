package dev.iredsc.hadron.selftest.hostile;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * Not a test: the well-behaved plugin {@link HostileTests} runs beside each broken one, to show the broken one costs
 * it nothing. Its timer counts ticks into {@link #TICKS}, its listener records chat in {@link #LOG}, and
 * {@code /healthy} answers "healthy ok".
 */
public final class HealthyPlugin extends JavaPlugin implements Listener {
    static final String TICKS = "hadron.selftest.healthy.ticks";
    static final String LOG = "hadron.selftest.healthy.log";

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (System.getProperties().get(TICKS) instanceof AtomicInteger n) n.incrementAndGet();
        }, 1, 1);
    }

    @EventHandler
    @SuppressWarnings("unchecked")
    public void onChat(PlayerChatEvent event) {
        if (System.getProperties().get(LOG) instanceof List<?> log) ((List<Object>) log).add("chat " + event.getRawText());
    }

    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        sender.sendSystemMessage(Component.literal("healthy ok"));
        return true;
    }
}
