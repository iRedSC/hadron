package dev.iredsc.hadron.selftest.plugin;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Not a test: the main class of a plugin jar {@link HostLifecycleTests} builds, with {@link HostProbeEvent} and
 * {@link HostProbeMenu}. On enable it fires its own event, opens its menu for the player named in
 * {@link #OPEN_FOR}, and throws if {@link #FAIL} is set. Told what to do, and reporting back, through the system
 * properties, the one place both class loaders share. No lambdas or inner classes, so the three class files are all.
 */
public final class HostProbePlugin extends JavaPlugin implements MenuProvider {
    static final String LOG = "hadron.selftest.hostprobe.log";
    static final String OPEN_FOR = "hadron.selftest.hostprobe.openFor";
    static final String FAIL = "hadron.selftest.hostprobe.fail";

    @SuppressWarnings("unchecked")
    private static void log(String line) {
        Object log = System.getProperties().get(LOG);
        if (log instanceof List) ((List<Object>) log).add(line);
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().callEvent(new HostProbeEvent());
        Object who = System.getProperties().get(OPEN_FOR);
        if (who != null) {
            ServerPlayer player = getServer().getMinecraftServer().getPlayerList().getPlayerByName(who.toString());
            if (player != null) player.openMenu(this);
        }
        log("enabled");
        if (System.getProperties().get(FAIL) != null) throw new IllegalStateException("told to fail");
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Probe");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new HostProbeMenu(id, inventory);
    }
}
