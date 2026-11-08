package dev.iredsc.hadron.api;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who may do what. Asks the running {@link PermissionProvider}; for anyone it says nothing about, a node falls back
 * to its {@link Default}. Safe from any thread. A provider that throws is logged and treated as saying nothing, so a
 * permissions plugin's bug falls back to the defaults instead of failing whoever asked.
 */
public final class Permissions {
    /** The group everyone is in. */
    public static final String DEFAULT_GROUP = "default";

    private static final Logger LOG = LoggerFactory.getLogger("Hadron");
    @StaticState(reason = "counts permission provider failures only to keep them from flooding the log")
    private static final AtomicInteger FAILURES = new AtomicInteger();

    private Permissions() {}

    public static boolean has(ServerPlayer player, Node node) {
        Boolean answer = ask(ranks -> ranks.check(player, node.id), null);
        return answer != null ? answer : fallback(player, node);
    }

    /** The provider's answer; {@code none} without a provider or when it throws (logged, a few times in full). */
    private static <T> @Nullable T ask(Function<PermissionProvider, T> question, @Nullable T none) {
        PermissionProvider ranks = Providers.get(PermissionProvider.class);
        if (ranks == null) return none;
        try {
            return question.apply(ranks);
        } catch (RuntimeException | LinkageError e) {
            int n = FAILURES.incrementAndGet();
            if (n <= 5 || n % 1000 == 0) {
                LOG.error("The permissions provider " + ranks.getClass().getName() + " threw (" + n + " times so far); "
                        + "answering from each node's default instead. Fix or remove the plugin that registers it", e);
            }
            return none;
        }
    }

    /** Players by their ranks; the console, command blocks and other sources by operator level 2. */
    public static boolean has(CommandSourceStack source, Node node) {
        ServerPlayer player = source.getPlayer();
        return player != null ? has(player, node) : source.hasPermission(2);
    }

    /** The name of the player's heaviest group, counting groups they're in through another. */
    public static String group(ServerPlayer player) {
        String group = ask(ranks -> ranks.group(player), DEFAULT_GROUP);
        return group == null ? DEFAULT_GROUP : group;
    }

    /** Is the player in the group, directly or through a group that inherits it? Everyone is in the default group. */
    public static boolean inGroup(ServerPlayer player, String group) {
        Boolean in = ask(ranks -> ranks.inGroup(player, group), null);
        return in != null ? in : group.equalsIgnoreCase(DEFAULT_GROUP);
    }

    /**
     * The prefix the permissions plugin picks, with colors applied; empty for none. It needn't come from
     * {@link #group}: LuckPerms, for one, shows the prefix with the highest priority, whichever group gives it.
     */
    public static String prefix(ServerPlayer player) {
        String prefix = ask(ranks -> ranks.prefix(player), "");
        return prefix == null ? "" : prefix;
    }

    /** The suffix the permissions plugin picks, as {@link #prefix} does. */
    public static String suffix(ServerPlayer player) {
        String suffix = ask(ranks -> ranks.suffix(player), "");
        return suffix == null ? "" : suffix;
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
