package dev.iredsc.hadron.luckperms;

import dev.iredsc.hadron.api.PermissionProvider;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginException;
import dev.iredsc.hadron.api.plugin.ServicePriority;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Hadron's ranks from LuckPerms: every {@link Permissions} check, group, prefix and suffix asks LuckPerms, in the
 * player's current contexts (world, gamemode and the rest LuckPerms tracks). A node LuckPerms says nothing about falls
 * back to its default, and a player LuckPerms hasn't loaded yet gets every default.
 *
 * <p>When LuckPerms recalculates a player (after {@code /lp user ... permission set}, a group change, a sync), their
 * command list is resent, so commands they gained or lost show up in their chat suggestions at once.
 */
public final class LuckPermsPlugin extends JavaPlugin implements Listener {
    private @Nullable EventSubscription<UserDataRecalculateEvent> recalculated;

    @Override
    public void onLoad() {
        LuckPerms luckPerms = luckPerms();
        getServer().getServicesManager().register(PermissionProvider.class, new Ranks(luckPerms), this,
                ServicePriority.Normal);
    }

    @Override
    public void onEnable() {
        LuckPerms luckPerms = luckPerms();
        MinecraftServer server = getServer().getMinecraftServer();
        getServer().getPluginManager().registerEvents(this, this);
        recalculated = luckPerms.getEventBus().subscribe(UserDataRecalculateEvent.class, event -> {
            UUID id = event.getUser().getUniqueId();
            // LuckPerms fires this off the server thread; the player list is only safe to read on it.
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) Permissions.refresh(player);
            });
        });
    }

    /**
     * The command tree sent while LuckPerms was still logging the player in used their server-wide ranks; now their
     * contexts (world, gamemode) are known, so it's sent again.
     */
    @EventHandler
    private void joined(PlayerJoinEvent event) {
        Permissions.refresh(event.getPlayer());
    }

    @Override
    public void onDisable() {
        // LuckPerms outlives a reload; left subscribed, the old version would keep its class loader alive.
        EventSubscription<UserDataRecalculateEvent> subscription = recalculated;
        if (subscription != null) subscription.close();
        recalculated = null;
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
