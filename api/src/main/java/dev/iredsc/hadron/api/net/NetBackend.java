package dev.iredsc.hadron.api.net;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * What a loader port implements behind {@link Packets}, {@link Visibility} and {@link DisplayNames}. Plugins use those
 * classes, never this.
 */
public interface NetBackend {
    PacketRegistration listen(JavaPlugin plugin, Class<? extends Packet<?>> type, EventPriority priority,
            PacketHandler<?> handler, boolean outgoing);

    void hide(JavaPlugin plugin, ServerPlayer viewer, Entity target);

    void show(JavaPlugin plugin, ServerPlayer viewer, Entity target);

    void hideFromAll(JavaPlugin plugin, Entity target);

    void showToAll(JavaPlugin plugin, Entity target);

    boolean canSee(ServerPlayer viewer, Entity target);

    boolean isHiddenFromAll(JavaPlugin plugin, Entity target);

    void setDisplayName(JavaPlugin plugin, ServerPlayer player, @Nullable Component name);

    void setPlayerListName(JavaPlugin plugin, ServerPlayer player, @Nullable Component name);

    void setNameTag(JavaPlugin plugin, ServerPlayer player, @Nullable String tag);

    /** For the loader port only. */
    final class Port {
        @StaticState(reason = "the port installs its one backend when the mod loads; Hadron itself is never reloaded")
        private static volatile @Nullable NetBackend backend;

        private Port() {}

        public static void install(NetBackend installed) {
            backend = Objects.requireNonNull(installed, "backend");
        }

        static NetBackend get() {
            NetBackend b = backend;
            if (b == null) throw new IllegalStateException("Hadron's packet API isn't installed: is the Hadron mod loaded?");
            return b;
        }
    }
}
