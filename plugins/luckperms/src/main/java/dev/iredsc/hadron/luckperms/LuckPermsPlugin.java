package dev.iredsc.hadron.luckperms;

import dev.iredsc.hadron.api.PermissionProvider;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginException;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;

/**
 * Hadron's ranks from LuckPerms: every {@link Permissions} check, group, prefix and suffix asks LuckPerms, in the
 * player's current contexts (world, gamemode and the rest LuckPerms tracks). A node LuckPerms says nothing about falls
 * back to its default, and a player LuckPerms hasn't loaded yet gets every default.
 *
 * <p>LuckPerms keeps each player's command list current itself ({@code update-client-command-list}, on by default),
 * asking Hadron's commands through this provider, so a rank change shows in their suggestions at once.
 */
public final class LuckPermsPlugin extends JavaPlugin {
    @Override
    public void onLoad() {
        getServer().getServicesManager().register(PermissionProvider.class, new Ranks(luckPerms()), this,
                ServicePriority.Normal);
    }

    private static LuckPerms luckPerms() {
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            throw new PluginException("LuckPerms is installed but never started, so there are no ranks to answer from. "
                    + "The server log's LuckPerms lines above say why it failed");
        }
    }
}
