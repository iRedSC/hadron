package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.Default;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.world.WorldLoadEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.PluginException;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Hadron's regions: boxes an operator protects, with owners, members, priorities and flags. It protects through
 * Hadron's events and actors, so it covers every mod whose bridge is installed, and refuses mods without one as
 * outsiders rather than letting them change a region unseen.
 */
public final class RegionsPlugin extends JavaPlugin implements Listener {
    static final Node MANAGE = Node.declare("regions.manage", Default.OPS, "Make, change and remove regions");
    static final Node MANAGE_OWNED = Node.declare("regions.manage.owned", Default.EVERYONE, "Change the members, "
            + "owners and flags of regions you own");
    static final Node BYPASS = Node.declare("regions.bypass", Default.OPS, "Turn on /region bypass, to build, use "
            + "and walk anywhere regions protect");

    private @Nullable RegionStore store;

    @Override
    public void onEnable() {
        RegionStore loaded;
        Bypass bypass;
        try {
            loaded = RegionStore.load(this);
            bypass = Bypass.load(this);
        } catch (RegionsFile.Problem p) {
            // Refusing to start leaves regions.yml untouched, so the fix is one edit away and nothing is lost.
            throw new PluginException(p.getMessage() + ". Regions stays off until it is fixed; the files are unchanged");
        }
        store = loaded;
        for (ServerLevel level : getServer().getMinecraftServer().getAllLevels()) loaded.watch(level);
        Feedback feedback = new Feedback(this);
        Wand wand = new Wand(feedback);
        getServer().getPluginManager().registerEvents(new Protection(this, loaded, feedback, bypass), this);
        getServer().getPluginManager().registerEvents(bypass, this);
        getServer().getPluginManager().registerEvents(wand, this);
        getServer().getPluginManager().registerEvents(this, this);
        RegionCommand command = new RegionCommand(this, loaded, wand, feedback, bypass);
        PluginCommand region = getCommand("region");
        region.setExecutor(command);
        region.setTabCompleter(command);
        getLogger().info("Protecting {} region(s)", loaded.all().size());
    }

    @EventHandler
    private void worldLoaded(WorldLoadEvent e) {
        if (store != null) store.watch(e.getLevel());
    }
}
