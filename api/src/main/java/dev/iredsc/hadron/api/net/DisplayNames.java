package dev.iredsc.hadron.api.net;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Nicknames, as EssentialsX and TAB set them: a player's name in chat, in the tab list and above their head, the same
 * for every viewer. Each setter takes {@code null} to go back to the player's own name.
 *
 * <p>Names are kept by UUID, so they survive relogs and respawns until reset. When a plugin disables, the names it
 * set are reset; a name another plugin set since is left alone.
 *
 * <p>Call on the server thread; the setters throw elsewhere. Resetting also works in {@code onDisable}.
 */
public final class DisplayNames {
    /** The longest name tag the protocol carries. */
    public static final int MAX_NAME_TAG_LENGTH = 16;

    private DisplayNames() {}

    /**
     * The name in chat, death and join messages, and wherever a mod asks for {@code player.getDisplayName()}, as
     * Bukkit's {@code setDisplayName}. Scoreboard team colours and prefixes still apply around it.
     */
    public static void setDisplayName(JavaPlugin plugin, ServerPlayer player, Component name) {
        NetBackend.Port.get().setDisplayName(plugin, player, name);
    }

    /** What chat shows for the player now: {@code player.getDisplayName()}. */
    public static Component getDisplayName(ServerPlayer player) {
        return player.getDisplayName();
    }

    /** The name in the tab list, as Bukkit's {@code setPlayerListName}. Any text and colours. */
    public static void setPlayerListName(JavaPlugin plugin, ServerPlayer player, Component name) {
        NetBackend.Port.get().setPlayerListName(plugin, player, name);
    }

    /**
     * The name above the player's head on everyone else's screen: plain text of 1 to {@value #MAX_NAME_TAG_LENGTH}
     * characters (colours come from scoreboard teams). Other clients then know the player by this name: it is also the
     * tab list name unless {@link #setPlayerListName} set one, and their clients suggest it for commands, which still
     * take the real name. Scores shown below the name stop showing, as vanilla keys them by the real name.
     *
     * @throws IllegalArgumentException if the tag is empty, too long, or has formatting codes or line breaks
     */
    public static void setNameTag(JavaPlugin plugin, ServerPlayer player, String tag) {
        NetBackend.Port.get().setNameTag(plugin, player, tag);
    }

    /** Puts back the player's own name in chat, the tab list and above their head. */
    public static void reset(JavaPlugin plugin, ServerPlayer player) {
        setDisplayName(plugin, player, null);
        setPlayerListName(plugin, player, null);
        setNameTag(plugin, player, null);
    }
}
