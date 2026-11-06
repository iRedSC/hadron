package dev.iredsc.hadron.api.net;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Bukkit's {@code hidePlayer} and {@code hideEntity}, for vanish and per-player NPCs. What is hidden from a viewer is
 * never sent to them: the entity is not spawned on their client (so a modded client can't see it either), a hidden
 * player's tab list entry is removed, their name is left out of command suggestions, and the viewer's clicks on it are
 * ignored. Showing it again sends everything back.
 *
 * <p>Each plugin hides separately: something hidden by two plugins stays hidden until both show it. Hiding is kept by
 * UUID, so it holds while a player is offline, across relogs, respawns and world changes, until shown again or until
 * the plugin disables, which shows everything it hid.
 *
 * <p>Still visible to a viewer: chat messages, join, quit and death messages, {@code /list}, the server list's player
 * sample, scoreboards, and sounds and particles the hidden one causes that vanilla doesn't tie to an entity (a block
 * placed or broken). Vanish plugins handle those through events.
 *
 * <p>Hide and show on the server thread; they throw elsewhere. Showing also works in {@code onDisable}, though
 * Hadron shows everything a plugin hid once it disables anyway. {@link #canSee} works on any thread.
 */
public final class Visibility {
    private Visibility() {}

    /** Hides {@code target} from {@code viewer}, as Bukkit's {@code viewer.hidePlayer(plugin, target)}. */
    public static void hidePlayer(JavaPlugin plugin, ServerPlayer viewer, ServerPlayer target) {
        hideEntity(plugin, viewer, target);
    }

    /** Undoes this plugin's {@link #hidePlayer}; other plugins may still hide the target. */
    public static void showPlayer(JavaPlugin plugin, ServerPlayer viewer, ServerPlayer target) {
        showEntity(plugin, viewer, target);
    }

    /** Hides any entity from one viewer. Hiding a viewer from themself does nothing. */
    public static void hideEntity(JavaPlugin plugin, ServerPlayer viewer, Entity target) {
        NetBackend.Port.get().hide(plugin, viewer, target);
    }

    /**
     * Undoes this plugin's {@link #hideEntity}. After {@link #hideFromAll}, this lets one viewer see the target again,
     * which is how staff see vanished players.
     */
    public static void showEntity(JavaPlugin plugin, ServerPlayer viewer, Entity target) {
        NetBackend.Port.get().show(plugin, viewer, target);
    }

    /**
     * Hides the target from every player, including players who join later, so a vanished player never flashes into
     * a joining player's tab list. {@link #showEntity} makes exceptions for single viewers.
     */
    public static void hideFromAll(JavaPlugin plugin, Entity target) {
        NetBackend.Port.get().hideFromAll(plugin, target);
    }

    /** Undoes everything this plugin hid of the target: {@link #hideFromAll} and every per-viewer hide. */
    public static void showToAll(JavaPlugin plugin, Entity target) {
        NetBackend.Port.get().showToAll(plugin, target);
    }

    /** False if any plugin hides the target from this viewer. A player always sees themself. */
    public static boolean canSee(ServerPlayer viewer, Entity target) {
        return NetBackend.Port.get().canSee(viewer, target);
    }

    /** Whether this plugin has hidden the target from every player with {@link #hideFromAll}. */
    public static boolean isHiddenFromAll(JavaPlugin plugin, Entity target) {
        return NetBackend.Port.get().isHiddenFromAll(plugin, target);
    }
}
