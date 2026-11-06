package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.PlayerMessages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the "left the game" line back for PlayerQuitEvent, which fires inside {@code removePlayerFromWorld} once the
 * player list removes them. Its own class, apart from {@code ServerGamePacketListenerImplMixin}, as it is the message
 * family's.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class QuitMessageMixin {
    @Shadow
    public ServerPlayer player;

    @WrapOperation(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void hadron$holdQuit(PlayerList list, Component message, boolean overlay, Operation<Void> original) {
        if (!PlayerMessages.holdQuit(player, message)) original.call(list, message, overlay);
    }

    @Inject(method = "removePlayerFromWorld", at = @At("RETURN"))
    private void hadron$flushQuit(CallbackInfo ci) {
        PlayerMessages.flushQuit(player);
    }
}
