package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import java.util.Collection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.PartEntity;

/** What the {@code mixin.net} mixins call. Each first check is a volatile read, so idle hooks cost nearly nothing. */
public final class NetHooks {
    private NetHooks() {}

    public static boolean sendActive() {
        return NetPackets.sendActive();
    }

    /** What to send instead, or null to send nothing. */
    public static @Nullable Packet<?> outgoing(ServerPlayer player, Packet<?> packet) {
        return NetPackets.outgoing(player, packet);
    }

    public static boolean receiveActive() {
        return NetPackets.receiveActive();
    }

    /** What to handle instead, or null to drop it. */
    public static @Nullable Packet<?> incoming(ServerPlayer player, Packet<?> packet) {
        return NetPackets.incoming(player, packet);
    }

    public static boolean visibilityActive() {
        return NetVisibility.active();
    }

    /** A multipart entity's part (the ender dragon's) is as visible as the entity it belongs to. */
    public static boolean canSee(ServerPlayer viewer, Entity target) {
        if (!NetVisibility.active()) return true;
        Entity whole = target instanceof PartEntity<?> part ? part.getParent() : target;
        return NetVisibility.canSee(viewer, whole);
    }

    public static Collection<String> visibleNames(ServerPlayer viewer, Collection<String> names) {
        return NetVisibility.names(viewer, names);
    }
}
