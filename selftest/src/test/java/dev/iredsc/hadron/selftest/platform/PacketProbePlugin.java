package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.checks.Nondeterministic;
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
    static final long SLOW_MILLIS = 300;
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
            if (text.equals("probe slow")) {
                // Sent from a test thread: still running when the server thread starts disabling the probe.
                log("slow started");
                holdUntilDisabling();
                log(closed ? "slow finished after onDisable" : "slow finished before onDisable");
            } else if (text.startsWith("probe")) {
                log((closed ? "handled after onDisable: " : "handled: ") + text);
            }
        });
    }

    /**
     * Stays inside the call until the disable has begun (the probe stops counting as enabled before its onDisable),
     * then a little longer: without a wait, onDisable runs meanwhile.
     */
    @Nondeterministic(reason = "holds a handler open on a sending thread while the server thread disables the plugin")
    private void holdUntilDisabling() {
        try {
            long until = System.nanoTime() + 10_000_000_000L;
            while (isEnabled() && System.nanoTime() < until) Thread.sleep(5);
            Thread.sleep(SLOW_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDisable() {
        closed = true;
        for (ServerPlayer p : List.copyOf(getServer().getMinecraftServer().getPlayerList().getPlayers())) {
            p.sendSystemMessage(Component.literal("probe goodbye"));
        }
    }
}
