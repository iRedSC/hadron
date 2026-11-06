package dev.iredsc.hadron.neoforge.mixin.net;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.NetHooks;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every packet the server sends a player in game: {@code player.connection.send}, tracking broadcasts, NeoForge's
 * payloads. Plugins' handlers and Hadron's visibility and name rules see it here, on the sending thread.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PacketSendMixin {
    @WrapMethod(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V")
    private void hadron$send(Packet<?> packet, PacketSendListener listener, Operation<Void> original) {
        if (NetHooks.sendActive() && (Object) this instanceof ServerGamePacketListenerImpl game) {
            packet = NetHooks.outgoing(game.player, packet);
            if (packet == null) return;
        }
        original.call(packet, listener);
    }
}
