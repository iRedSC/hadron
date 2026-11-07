package dev.iredsc.hadron.neoforge;

import com.mojang.authlib.GameProfile;
import dev.iredsc.hadron.api.net.DisplayNames;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.neoforge.mixin.net.SetPlayerTeamPacketAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Nicknames. Chat and tab list names go through NeoForge's own name events, so every viewer and every mod asking for
 * {@code getDisplayName()} sees them. The name above the head is the name in the player's profile on other clients,
 * so it is rewritten in the tab list packets that carry the profile, and in team packets so team colours follow it.
 */
final class NetNames {
    private NetNames() {}

    record Owned<T>(JavaPlugin plugin, T value) {}

    private static final Map<UUID, Owned<Component>> DISPLAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Owned<Component>> LIST = new ConcurrentHashMap<>();
    private static final Map<UUID, Owned<String>> TAGS = new ConcurrentHashMap<>();
    /** Real name to tag, for team packets, which name players rather than carry their UUID. */
    private static final Map<String, String> TAG_BY_NAME = new ConcurrentHashMap<>();
    /** Each tagged player's real name, kept while they are offline so TAG_BY_NAME can be rebuilt. */
    private static final Map<UUID, String> REAL_NAME = new ConcurrentHashMap<>();
    private static volatile boolean tags;

    static boolean tagsActive() {
        return tags;
    }

    // ---- NeoForge's name events ----

    static void nameFormat(PlayerEvent.NameFormat event) {
        // Server players only: in singleplayer the client's own copy of the player shares the UUID.
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Owned<Component> o = DISPLAY.get(p.getUUID());
        if (o != null) event.setDisplayname(o.value());
    }

    static void tabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Owned<Component> o = LIST.get(p.getUUID());
        if (o != null) event.setDisplayName(o.value());
    }

    // ---- Setters (server thread) ----

    static void setDisplayName(JavaPlugin plugin, ServerPlayer player, Component name) {
        check(plugin, player, name, "set display names");
        if (put(DISPLAY, plugin, player.getUUID(), name)) player.refreshDisplayName();
    }

    static void setPlayerListName(JavaPlugin plugin, ServerPlayer player, Component name) {
        check(plugin, player, name, "set tab list names");
        if (put(LIST, plugin, player.getUUID(), name)) player.refreshTabListName();
    }

    static void setNameTag(JavaPlugin plugin, ServerPlayer player, String tag) {
        check(plugin, player, tag, "set name tags");
        if (tag != null) validate(tag);
        String real = player.getGameProfile().getName();
        if (!put(TAGS, plugin, player.getUUID(), tag)) return;
        if (tag == null) {
            TAG_BY_NAME.remove(real);
            REAL_NAME.remove(player.getUUID());
        } else {
            TAG_BY_NAME.put(real, tag);
            REAL_NAME.put(player.getUUID(), real);
        }
        tags = !TAGS.isEmpty();
        resendProfile(player);
    }

    private static void validate(String tag) {
        if (tag.isEmpty() || tag.length() > DisplayNames.MAX_NAME_TAG_LENGTH) {
            throw new IllegalArgumentException("A name tag is 1 to " + DisplayNames.MAX_NAME_TAG_LENGTH
                    + " characters; \"" + tag + "\" has " + tag.length() + ". Use setDisplayName or setPlayerListName "
                    + "for longer names");
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c == '§' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("A name tag is plain text: \"" + tag + "\" has a formatting code or "
                        + "control character. Colour it with a scoreboard team instead");
            }
        }
    }

    /** Resetting (a null value) is allowed from onDisable; setting needs an enabled plugin. */
    private static void check(JavaPlugin plugin, ServerPlayer player, Object value, String what) {
        NetPort.requireServerThread("DisplayNames");
        if (plugin == null || player == null) {
            throw new IllegalArgumentException("DisplayNames needs the plugin and the player; one was null");
        }
        if (value != null && !plugin.isEnabled()) {
            throw new IllegalStateException(plugin.getName() + " can't " + what + " while it isn't enabled");
        }
    }

    /** Sets or clears the value; true if it changed. */
    private static <T> boolean put(Map<UUID, Owned<T>> map, JavaPlugin plugin, UUID id, T value) {
        Owned<T> old = value == null ? map.remove(id) : map.put(id, new Owned<T>(plugin, value));
        return !Objects.equals(old == null ? null : old.value(), value);
    }

    /** Disable hook: resets the names this plugin set that nobody has overwritten since. */
    static void remove(JavaPlugin plugin) {
        MinecraftServer server = NetPort.serverOrNull();
        for (UUID id : drop(DISPLAY, plugin)) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
            if (p != null) p.refreshDisplayName();
        }
        for (UUID id : drop(LIST, plugin)) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
            if (p != null) p.refreshTabListName();
        }
        List<UUID> tagged = drop(TAGS, plugin);
        if (tagged.isEmpty()) return;
        for (UUID id : tagged) REAL_NAME.remove(id);
        TAG_BY_NAME.clear();
        for (Map.Entry<UUID, Owned<String>> e : TAGS.entrySet()) {
            String real = REAL_NAME.get(e.getKey());
            if (real != null) TAG_BY_NAME.put(real, e.getValue().value());
        }
        tags = !TAGS.isEmpty();
        for (UUID id : tagged) {
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
            if (p != null) resendProfile(p);
        }
    }

    private static <T> List<UUID> drop(Map<UUID, Owned<T>> map, JavaPlugin plugin) {
        List<UUID> out = new ArrayList<UUID>();
        map.entrySet().removeIf(e -> {
            if (e.getValue().plugin() != plugin) return false;
            out.add(e.getKey());
            return true;
        });
        return out;
    }

    /** A client reads a player's profile name only when it spawns them, so each viewer gets the player afresh. */
    private static void resendProfile(ServerPlayer target) {
        PlayerTeam team = target.getTeam();
        for (ServerPlayer viewer : new ArrayList<>(target.server.getPlayerList().getPlayers())) {
            if (viewer == target || !NetVisibility.canSee(viewer, target)) continue;
            NetPort.untrack(viewer, target);
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(target.getUUID())));
            viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(target)));
            if (team != null) {
                viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(team,
                        target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
            }
            NetPort.retrack(viewer, target);
        }
    }

    // ---- What reaches a viewer ----

    /** Puts name tags into profiles and team member lists sent to everyone but the tagged player themself. */
    static Packet<?> rewrite(ServerPlayer viewer, Packet<?> packet) {
        if (!tags) return packet;
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info) return profiles(viewer, info);
        if (packet instanceof ClientboundSetPlayerTeamPacket team) return team(viewer, team);
        return packet;
    }

    private static Packet<?> profiles(ServerPlayer viewer, ClientboundPlayerInfoUpdatePacket packet) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return packet;
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries();
        List<ClientboundPlayerInfoUpdatePacket.Entry> out = null;
        for (int i = 0; i < entries.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry e = entries.get(i);
            Owned<String> tag = e.profile() == null || e.profileId().equals(viewer.getUUID()) ? null
                    : TAGS.get(e.profileId());
            if (tag != null && out == null) out = new ArrayList<>(entries.subList(0, i));
            if (out == null) continue;
            if (tag == null) {
                out.add(e);
                continue;
            }
            GameProfile renamed = new GameProfile(e.profileId(), tag.value());
            renamed.getProperties().putAll(e.profile().getProperties());
            out.add(new ClientboundPlayerInfoUpdatePacket.Entry(e.profileId(), renamed, e.listed(), e.latency(),
                    e.gameMode(), e.displayName(), e.chatSession()));
        }
        return out == null ? packet : NetPort.infoPacket(packet.actions(), out);
    }

    private static Packet<?> team(ServerPlayer viewer, ClientboundSetPlayerTeamPacket packet) {
        Collection<String> players = packet.getPlayers();
        if (players.isEmpty()) return packet;
        String self = viewer.getGameProfile().getName();
        List<String> out = null;
        int i = 0;
        for (String name : players) {
            String tag = name.equals(self) ? null : TAG_BY_NAME.get(name);
            if (tag != null && out == null) {
                out = new ArrayList<String>();
                int j = 0;
                for (String earlier : players) {
                    if (j++ == i) break;
                    out.add(earlier);
                }
            }
            if (out != null) out.add(tag != null ? tag : name);
            i++;
        }
        if (out == null) return packet;
        return SetPlayerTeamPacketAccessor.hadron$create(packet.getName(),
                ((SetPlayerTeamPacketAccessor) packet).hadron$method(), packet.getParameters(), out);
    }
}
