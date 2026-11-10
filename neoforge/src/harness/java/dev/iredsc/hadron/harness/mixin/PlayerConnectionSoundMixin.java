package dev.iredsc.hadron.harness.mixin;

import dev.iredsc.hadron.harness.TestSideEffects;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every packet the server sends a player in game, for {@code SideEffects}: where a sound sent straight to one player
 * ({@code /playsound}, a raid's horn, a portal's whoosh, a locked chest) shows up, since no world broadcasts it.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class PlayerConnectionSoundMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"))
    private void hadron$recordSentSound(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game) TestSideEffects.packetSent(game.player, packet);
    }
}
