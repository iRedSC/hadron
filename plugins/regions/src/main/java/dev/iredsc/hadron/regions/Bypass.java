package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.config.InvalidConfigurationException;
import dev.iredsc.hadron.api.config.YamlConfiguration;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who has turned on {@code /region bypass}, saved in {@code bypass.yml} so it lasts across leaving and restarts. It
 * lets a player through regions only while they also hold {@link RegionsPlugin#BYPASS}: taking the node away ends it
 * at once, and an op starts with it off, so they see their regions protect.
 */
final class Bypass implements Listener {
    private final File file;
    private final Set<UUID> on = new LinkedHashSet<>();

    private Bypass(File file) {
        this.file = file;
    }

    static Bypass load(JavaPlugin plugin) throws RegionsFile.Problem {
        Bypass bypass = new Bypass(new File(plugin.getDataFolder(), "bypass.yml"));
        if (!bypass.file.exists()) return bypass;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(bypass.file);
        } catch (IOException e) {
            throw new RegionsFile.Problem("Couldn't read " + bypass.file + ": " + e.getMessage());
        } catch (InvalidConfigurationException e) {
            throw new RegionsFile.Problem("bypass.yml isn't valid YAML: " + e.getMessage());
        }
        bypass.on.addAll(read(yaml));
        return bypass;
    }

    static Set<UUID> read(YamlConfiguration yaml) throws RegionsFile.Problem {
        Set<UUID> out = new LinkedHashSet<>();
        for (String text : yaml.getStringList("players")) {
            try {
                out.add(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                throw new RegionsFile.Problem("bypass.yml, players: \"" + text + "\" isn't a player UUID; remove "
                        + "that line, and the player turns bypass on again with /region bypass");
            }
        }
        return out;
    }

    static YamlConfiguration write(Collection<UUID> players) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("players", players.stream().map(UUID::toString).sorted().toList());
        return yaml;
    }

    /** Whether regions let this player through right now. */
    boolean active(ServerPlayer player) {
        return on.contains(player.getUUID()) && Permissions.has(player, RegionsPlugin.BYPASS);
    }

    /**
     * Flips it and saves.
     *
     * @return whether it is now on.
     * @throws UncheckedIOException if bypass.yml can't be written; the change holds until the server stops.
     */
    boolean toggle(ServerPlayer player) {
        boolean now = !on.remove(player.getUUID());
        if (now) on.add(player.getUUID());
        try {
            write(on).save(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save " + file + ", so this lasts only until the server stops: "
                    + e.getMessage(), e);
        }
        return now;
    }

    static Component status(boolean active) {
        return active
                ? Component.literal("Region bypass is on · /region bypass").withStyle(ChatFormatting.GOLD)
                : Component.literal("Region bypass is off").withStyle(ChatFormatting.GRAY);
    }

    /** Someone joining with it on is told, so they never grief a region without knowing they can. */
    @EventHandler
    private void join(PlayerJoinEvent e) {
        if (active(e.getPlayer())) e.getPlayer().sendSystemMessage(status(true));
    }
}
