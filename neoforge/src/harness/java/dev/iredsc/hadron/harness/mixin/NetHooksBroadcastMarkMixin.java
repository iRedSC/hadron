package dev.iredsc.hadron.harness.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.harness.TestSideEffects;
import dev.iredsc.hadron.neoforge.NetHooks;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The one place a packet on its way to a player is replaced (a plugin's {@code setPacket}, Hadron's name rewriting),
 * for {@code SideEffects}: a replacement of a broadcast's packet is still that broadcast's copy, so it inherits the
 * mark ({@link TestSideEffects#replaced}).
 */
@Mixin(NetHooks.class)
abstract class NetHooksBroadcastMarkMixin {
    @WrapMethod(method = "outgoing")
    private static @Nullable Packet<?> hadron$recordReplacement(ServerPlayer player, Packet<?> packet,
            Operation<@Nullable Packet<?>> original) {
        @Nullable Packet<?> sent = original.call(player, packet);
        if (sent != null && sent != packet) TestSideEffects.replaced(packet, sent);
        return sent;
    }
}
