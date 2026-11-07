package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Every server-side disconnect of an in-game player: /kick, timeouts, mods, rejected packets. Vanilla rejects some
 * packets on the connection's thread (a bad keep-alive, illegal chat characters, chat out of order); those kicks move
 * to the server thread, decision and disconnect together, so listeners never run off it.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Shadow @Final protected MinecraftServer server;

    @WrapMethod(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V")
    private void hadron$kick(DisconnectionDetails details, Operation<Void> original) {
        // Shutdown and a newer login of the same account can't be refused.
        if ((Object) this instanceof ServerGamePacketListenerImpl game && server.isRunning()
                && details.reason() != PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE) {
            if (!server.isSameThread()) {
                DisconnectionDetails asked = details;
                server.execute(() -> {
                    if (game.isAcceptingMessages()) hadron$kick(asked, original);
                });
                return;
            }
            Component reason = WorldHooks.kick(game.player, details.reason());
            if (reason == null) return;
            if (reason != details.reason()) {
                details = new DisconnectionDetails(reason, details.report(), details.bugReportLink());
            }
        }
        original.call(details);
    }
}
