package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.net.Packets;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * A plugin packed into its own jar by {@link NamesAndPacketsTests}: it handles chat packets, and in {@code onDisable}
 * closes what its handler uses, then sends a chat line, as a plugin saying goodbye does. Its handler must not run once
 * it is disabling. It reports through a list in the system properties, the one thing both class loaders share.
 */
public final class PacketProbePlugin extends JavaPlugin {
    static final String LOG = "hadron.selftest.packetProbe";
    private volatile boolean closed;

    @SuppressWarnings("unchecked")
    private static void log(String line) {
        Object list = System.getProperties().get(LOG);
        if (list instanceof List<?> l) ((List<Object>) l).add(line);
    }

    @Override
    public void onEnable() {
        Packets.onSend(this, ClientboundSystemChatPacket.class, e -> {
            String text = e.getPacket().content().getString();
            if (text.startsWith("probe")) log((closed ? "handled after onDisable: " : "handled: ") + text);
        });
    }

    @Override
    public void onDisable() {
        closed = true;
        for (ServerPlayer p : List.copyOf(getServer().getMinecraftServer().getPlayerList().getPlayers())) {
            p.sendSystemMessage(Component.literal("probe goodbye"));
        }
    }
}
