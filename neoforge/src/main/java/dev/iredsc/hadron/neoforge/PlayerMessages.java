package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.player.PlayerDeathEvent;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;

/**
 * The join, quit and death lines, held back from vanilla's broadcast while a plugin listens, so the event can replace
 * them instead of adding to them. Vanilla sends each line before the event fires; the mixins stop that send and park
 * the line here, the event takes it, and the event's choice is sent. If the event never fires, the line goes out
 * unchanged when the vanilla method returns, so a line is never lost. Server thread only.
 */
public final class PlayerMessages {
    /** A held line; {@code message} null when vanilla would have sent none. */
    private record Held(Component message) {}

    private static final Map<ServerPlayer, Held> JOINS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Held> QUITS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Held> DEATHS = new WeakHashMap<>();

    private PlayerMessages() {}

    private static boolean shows(Component message) {
        return message != null && !message.getString().isEmpty();
    }

    private static void broadcast(ServerPlayer player, Component message) {
        if (shows(message)) player.server.getPlayerList().broadcastSystemMessage(message, false);
    }

    // ---- Join and quit ------------------------------------------------------------------------------------------

    /** Vanilla is about to broadcast the join line. True when it is held for {@link PlayerJoinEvent}. */
    public static boolean holdJoin(ServerPlayer player, Component message) {
        if (!Hooks.listens(PlayerJoinEvent.class)) return false;
        JOINS.put(player, new Held(message));
        return true;
    }

    /** The held join line, or null if none was held; taken, so it is sent once. */
    static Component takeJoin(ServerPlayer player) {
        Held held = JOINS.remove(player);
        return held == null ? null : held.message();
    }

    /** Sends the line the join event settled on. */
    static void join(ServerPlayer player, Component message) {
        broadcast(player, message);
    }

    /** {@code placeNewPlayer} returned: a line nobody took goes out as vanilla's. */
    public static void flushJoin(ServerPlayer player) {
        Held held = JOINS.remove(player);
        if (held != null) broadcast(player, held.message());
    }

    /** Vanilla is about to broadcast the quit line. True when it is held for {@link PlayerQuitEvent}. */
    public static boolean holdQuit(ServerPlayer player, Component message) {
        if (!Hooks.listens(PlayerQuitEvent.class)) return false;
        QUITS.put(player, new Held(message));
        return true;
    }

    static Component takeQuit(ServerPlayer player) {
        Held held = QUITS.remove(player);
        return held == null ? null : held.message();
    }

    static void quit(ServerPlayer player, Component message) {
        broadcast(player, message);
    }

    /** {@code removePlayerFromWorld} returned: a line nobody took goes out as vanilla's. */
    public static void flushQuit(ServerPlayer player) {
        Held held = QUITS.remove(player);
        if (held != null) broadcast(player, held.message());
    }

    // ---- Death --------------------------------------------------------------------------------------------------

    /**
     * {@code ServerPlayer.die} read the {@code showDeathMessages} rule. True when the death screen and line are held
     * for {@link PlayerDeathEvent}; vanilla then takes its no-message branch, whose packet the mixin skips.
     */
    public static boolean holdDeath(ServerPlayer player, boolean show) {
        if (!Hooks.listens(PlayerDeathEvent.class)) return false;
        DEATHS.put(player, new Held(show ? player.getCombatTracker().getDeathMessage() : null));
        return true;
    }

    /** Whether this player's death screen is held, so vanilla's empty one must not be sent. */
    public static boolean deathHeld(ServerPlayer player) {
        return DEATHS.containsKey(player);
    }

    static Component takeDeath(ServerPlayer player) {
        Held held = DEATHS.remove(player);
        return held == null ? null : held.message();
    }

    /** {@code die} returned: a death nobody reported goes out as vanilla's. */
    public static void flushDeath(ServerPlayer player) {
        Held held = DEATHS.remove(player);
        if (held != null) death(player, held.message());
    }

    /** Shows the death screen with this line, and broadcasts it to whom the player's team allows, as vanilla does. */
    static void death(ServerPlayer player, Component message) {
        boolean shown = shows(message);
        int id = player.getId();
        player.connection.send(new ClientboundPlayerCombatKillPacket(id, shown ? message : CommonComponents.EMPTY),
                PacketSendListener.exceptionallySend(() -> new ClientboundPlayerCombatKillPacket(id,
                        CommonComponents.EMPTY))); // a line too long for the packet still gets the death screen
        if (!shown) return;
        Team team = player.getTeam();
        var players = player.server.getPlayerList();
        if (team == null || team.getDeathMessageVisibility() == Team.Visibility.ALWAYS) {
            players.broadcastSystemMessage(message, false);
        } else if (team.getDeathMessageVisibility() == Team.Visibility.HIDE_FOR_OTHER_TEAMS) {
            players.broadcastSystemToTeam(player, message);
        } else if (team.getDeathMessageVisibility() == Team.Visibility.HIDE_FOR_OWN_TEAM) {
            players.broadcastSystemToAllExceptTeam(player, message);
        }
    }
}
