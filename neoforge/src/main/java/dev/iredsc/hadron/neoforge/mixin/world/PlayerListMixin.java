package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.PlayerMessages;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds the "joined the game" line back for PlayerJoinEvent, which fires at the end of {@code placeNewPlayer}. */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
    private void hadron$holdJoin(PlayerList list, Component message, boolean overlay, Operation<Void> original,
            @Local(argsOnly = true) ServerPlayer player) {
        if (!PlayerMessages.holdJoin(player, message)) original.call(list, message, overlay);
    }

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void hadron$flushJoin(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
            CallbackInfo ci) {
        PlayerMessages.flushJoin(player);
    }
}
