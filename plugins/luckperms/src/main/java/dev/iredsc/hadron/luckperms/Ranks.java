package dev.iredsc.hadron.luckperms;

import dev.iredsc.hadron.api.PermissionProvider;
import dev.iredsc.hadron.api.Permissions;
import java.util.Collection;
import java.util.List;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** {@link PermissionProvider} answered by LuckPerms for the player's current contexts. */
final class Ranks implements PermissionProvider {
    private final LuckPerms luckPerms;

    Ranks(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    @Override
    public @Nullable Boolean check(ServerPlayer player, String node) {
        User user = user(player);
        if (user == null) return null;
        Tristate answer = user.getCachedData().getPermissionData(options(player)).checkPermission(node);
        return answer == Tristate.UNDEFINED ? null : answer.asBoolean();
    }

    /**
     * The heaviest of the groups the player is in, directly or through another, by LuckPerms' group weight; among
     * equal weights, the first LuckPerms lists. Chosen here rather than by LuckPerms' primary group, whose default
     * setting follows only the groups a player was given directly.
     */
    @Override
    public String group(ServerPlayer player) {
        Group heaviest = null;
        for (Group group : groups(player)) {
            if (heaviest == null || group.getWeight().orElse(0) > heaviest.getWeight().orElse(0)) heaviest = group;
        }
        return heaviest == null ? Permissions.DEFAULT_GROUP : heaviest.getName();
    }

    /**
     * Membership itself, directly or through a group that inherits it, in the player's current contexts; never a
     * {@code group.<name>} permission check, which a {@code *} grant would answer yes for any name.
     */
    @Override
    public boolean inGroup(ServerPlayer player, String group) {
        if (group.equalsIgnoreCase(Permissions.DEFAULT_GROUP)) return true;
        for (Group in : groups(player)) {
            if (in.getName().equalsIgnoreCase(group)) return true;
        }
        return false;
    }

    @Override
    public String prefix(ServerPlayer player) {
        CachedMetaData meta = meta(player);
        return Colors.translate(meta == null ? null : meta.getPrefix());
    }

    @Override
    public String suffix(ServerPlayer player) {
        CachedMetaData meta = meta(player);
        return Colors.translate(meta == null ? null : meta.getSuffix());
    }

    private Collection<Group> groups(ServerPlayer player) {
        User user = user(player);
        return user == null ? List.of() : user.getInheritedGroups(options(player));
    }

    private @Nullable CachedMetaData meta(ServerPlayer player) {
        User user = user(player);
        return user == null ? null : user.getCachedData().getMetaData(options(player));
    }

    /** Null while LuckPerms hasn't loaded the player, as during login. */
    private @Nullable User user(ServerPlayer player) {
        return luckPerms.getUserManager().getUser(player.getUUID());
    }

    /**
     * The player's contexts. Some LuckPerms builds can't say yet while a player is still logging in (5.4.140 throws),
     * so their server-wide ranks answer for those few ticks instead of an error.
     */
    private QueryOptions options(ServerPlayer player) {
        try {
            return luckPerms.getPlayerAdapter(ServerPlayer.class).getQueryOptions(player);
        } catch (IllegalStateException loggingIn) {
            return luckPerms.getContextManager().getStaticQueryOptions();
        }
    }
}
