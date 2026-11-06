package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.iredsc.hadron.api.event.player.PlayerToggleFlightEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleSneakEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleSprintEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.PlayerState;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sneak, sprint and flight toggles as the player asks for them, and the abilities baseline at login. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    /** Login and fake players: the client is about to be told the abilities the player loaded with. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void hadron$baseline(CallbackInfo ci) {
        ((PlayerState) player).hadron$syncAbilities();
    }

    @WrapWithCondition(method = "handlePlayerCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setShiftKeyDown(Z)V"))
    private boolean hadron$sneak(ServerPlayer p, boolean sneaking) {
        if (p.isShiftKeyDown() == sneaking || !Hooks.listens(PlayerToggleSneakEvent.class)) return true;
        return !Hooks.post(new PlayerToggleSneakEvent(p, sneaking)).isCancelled();
    }

    @WrapWithCondition(method = "handlePlayerCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setSprinting(Z)V"))
    private boolean hadron$sprint(ServerPlayer p, boolean sprinting) {
        if (p.isSprinting() == sprinting || !Hooks.listens(PlayerToggleSprintEvent.class)) return true;
        return !Hooks.post(new PlayerToggleSprintEvent(p, sprinting)).isCancelled();
    }

    @Inject(method = "handlePlayerAbilities", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;"
                    + "Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void hadron$flight(ServerboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        boolean flying = packet.isFlying() && player.mayFly();
        if (flying == player.getAbilities().flying || !Hooks.listens(PlayerToggleFlightEvent.class)) return;
        if (Hooks.post(new PlayerToggleFlightEvent(player, flying)).isCancelled()) {
            ci.cancel();
            player.onUpdateAbilities();
        }
    }
}
