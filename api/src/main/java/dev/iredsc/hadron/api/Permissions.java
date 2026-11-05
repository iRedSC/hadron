package dev.iredsc.hadron.api;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who may do what. Asks the running {@link PermissionProvider}; for anyone it says nothing about, a node falls back
 * to its {@link Default}. Safe from any thread.
 */
public final class Permissions {
    /** The group everyone is in. */
    public static final String DEFAULT_GROUP = "default";

    private Permissions() {}

    public static boolean has(ServerPlayer player, Node node) {
        PermissionProvider ranks = Providers.get(PermissionProvider.class);
        Boolean answer = ranks == null ? null : ranks.check(player, node.id);
        return answer != null ? answer : fallback(player, node);
    }

    /** Players by their ranks; the console, command blocks and other sources by operator level 2. */
    public static boolean has(CommandSourceStack source, Node node) {
        ServerPlayer player = source.getPlayer();
        return player != null ? has(player, node) : source.hasPermission(2);
    }

    /** The name of the player's heaviest group, which supplies their prefix first. */
    public static String group(ServerPlayer player) {
        PermissionProvider ranks = Providers.get(PermissionProvider.class);
        return ranks == null ? DEFAULT_GROUP : ranks.group(player);
    }

    /** Is the player in the group, directly or through a group that inherits it? Everyone is in the default group. */
    public static boolean inGroup(ServerPlayer player, String group) {
        PermissionProvider ranks = Providers.get(PermissionProvider.class);
        return ranks == null ? group.equalsIgnoreCase(DEFAULT_GROUP) : ranks.inGroup(player, group);
    }

    public static String prefix(ServerPlayer player) {
        PermissionProvider ranks = Providers.get(PermissionProvider.class);
        return ranks == null ? "" : ranks.prefix(player);
    }

    public static String suffix(ServerPlayer player) {
        PermissionProvider ranks = Providers.get(PermissionProvider.class);
        return ranks == null ? "" : ranks.suffix(player);
    }

    /** Who has a node no rank decides: its default. */
    public static boolean fallback(ServerPlayer player, Node node) {
        return switch (node.fallback) {
            case EVERYONE -> true;
            case OPS -> player.server.getPlayerList().isOp(player.getGameProfile());
            case NOBODY -> false;
        };
    }

    /**
     * Resends the player's command list, so commands they gained or lost appear or disappear in their chat
     * suggestions. Permissions plugins call this after changing someone's ranks.
     */
    public static void refresh(ServerPlayer player) {
        MinecraftServer server = player.server;
        server.execute(() -> server.getCommands().sendCommands(player));
    }
}
