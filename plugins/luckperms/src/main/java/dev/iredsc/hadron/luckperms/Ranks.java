package dev.iredsc.hadron.luckperms;

import dev.iredsc.hadron.api.PermissionProvider;
import dev.iredsc.hadron.api.Permissions;
import java.util.Locale;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** {@link PermissionProvider} answered by LuckPerms' cached data for the player's current contexts. */
final class Ranks implements PermissionProvider {
    private final LuckPerms luckPerms;

    Ranks(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    @Override
    public @Nullable Boolean check(ServerPlayer player, String node) {
        CachedDataManager data = data(player);
        if (data == null) return null;
        Tristate answer = data.getPermissionData(options(player)).checkPermission(node);
        return answer == Tristate.UNDEFINED ? null : answer.asBoolean();
    }

    @Override
    public String group(ServerPlayer player) {
        CachedDataManager data = data(player);
        String group = data == null ? null : data.getMetaData(options(player)).getPrimaryGroup();
        return group == null ? Permissions.DEFAULT_GROUP : group;
    }

    /** Through LuckPerms' {@code group.<name>} node, so a group inherited through another counts too. */
    @Override
    public boolean inGroup(ServerPlayer player, String group) {
        if (group.equalsIgnoreCase(Permissions.DEFAULT_GROUP)) return true;
        return Boolean.TRUE.equals(check(player, "group." + group.toLowerCase(Locale.ROOT)));
    }

    @Override
    public String prefix(ServerPlayer player) {
        CachedDataManager data = data(player);
        return Colors.translate(data == null ? null : data.getMetaData(options(player)).getPrefix());
    }

    @Override
    public String suffix(ServerPlayer player) {
        CachedDataManager data = data(player);
        return Colors.translate(data == null ? null : data.getMetaData(options(player)).getSuffix());
    }

    /** Null while LuckPerms hasn't loaded the player, as during login. */
    private @Nullable CachedDataManager data(ServerPlayer player) {
        User user = luckPerms.getUserManager().getUser(player.getUUID());
        return user == null ? null : user.getCachedData();
    }

    /**
     * The player's contexts. While LuckPerms is still logging them in it can't say yet, so their server-wide ranks
     * answer for those few ticks; {@link LuckPermsPlugin} resends their commands once they've joined.
     */
    private QueryOptions options(ServerPlayer player) {
        try {
            return luckPerms.getPlayerAdapter(ServerPlayer.class).getQueryOptions(player);
        } catch (IllegalStateException loggingIn) {
            return luckPerms.getContextManager().getStaticQueryOptions();
        }
    }
}
