package dev.iredsc.hadron.neoforge.mixin.net;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.NetHooks;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Every packet a player in game sends, on its network thread, before vanilla handles or reschedules it. */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @WrapOperation(method = "channelRead0", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;"
            + "genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void hadron$receive(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (NetHooks.receiveActive() && listener instanceof ServerGamePacketListenerImpl game) {
            packet = NetHooks.incoming(game.player, packet);
            if (packet == null) return;
        }
        original.call(packet, listener);
    }
}
