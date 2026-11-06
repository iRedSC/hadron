package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PlayerLoginEvent: after vanilla's checks, with the player made but not yet placed in the world. */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin {
    @Inject(method = "handleConfigurationFinished", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;"
                    + "Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"))
    private void hadron$login(ServerboundFinishConfigurationPacket packet, CallbackInfo ci,
            @Local ServerPlayer player) {
        ServerCommonPacketListenerImpl self = (ServerCommonPacketListenerImpl) (Object) this;
        Component denied = WorldHooks.loginDenied(player, self.getConnection().getRemoteAddress());
        if (denied != null) {
            self.disconnect(denied);
            ci.cancel();
        }
    }
}
