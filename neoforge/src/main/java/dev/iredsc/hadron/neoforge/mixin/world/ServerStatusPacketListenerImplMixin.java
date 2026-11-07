package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.ServerPings;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ServerListPingEvent, as the status answer is built. NeoForge caches the answer's JSON; a changed answer drops the
 * cache so the change is what gets sent.
 */
@Mixin(ServerStatusPacketListenerImpl.class)
abstract class ServerStatusPacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @WrapOperation(method = "handleStatusRequest", at = @At(value = "NEW",
            target = "(Lnet/minecraft/network/protocol/status/ServerStatus;Ljava/lang/String;)Lnet/minecraft/network/protocol/status/ClientboundStatusResponsePacket;"))
    private ClientboundStatusResponsePacket hadron$ping(ServerStatus status, String cachedJson,
            Operation<ClientboundStatusResponsePacket> original) {
        ServerStatus answer = ServerPings.ping(connection.getRemoteAddress(), status);
        return answer == status ? original.call(status, cachedJson) : original.call(answer, null);
    }
}
