package dev.iredsc.hadron.api;

import org.jspecify.annotations.Nullable;
import net.minecraft.server.level.ServerPlayer;

/**
 * Ranks: what a permissions plugin registers with the services manager, in its onLoad, for {@link Permissions} to
 * ask. With none running, every node falls back to its {@link Default}.
 *
 * <p>After a player's ranks change, call {@link Permissions#refresh} so their client shows the commands they may now
 * use.
 */
public interface PermissionProvider {
    /** True or false when the player's ranks grant or deny the node id; null when they don't say. */
    @Nullable Boolean check(ServerPlayer player, String node);

    /** The name of the player's heaviest group. */
    String group(ServerPlayer player);

    boolean inGroup(ServerPlayer player, String group);

    /** With colors applied; empty for none. */
    String prefix(ServerPlayer player);

    String suffix(ServerPlayer player);
}
