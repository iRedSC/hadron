package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.PlayerMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds a player's death screen and line back for PlayerDeathEvent, which fires later in {@code die} once the drops
 * are known. While held, vanilla sees the {@code showDeathMessages} rule as off and its empty death screen is not
 * sent; {@code PlayerMessages} sends the event's choice instead, or vanilla's if the event never fired.
 */
@Mixin(ServerPlayer.class)
abstract class DeathMessageMixin {
    @ModifyExpressionValue(method = "die", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean hadron$holdDeath(boolean show) {
        return !PlayerMessages.holdDeath((ServerPlayer) (Object) this, show) && show;
    }

    @WrapOperation(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void hadron$skipEmptyScreen(ServerGamePacketListenerImpl connection, Packet<?> packet,
            Operation<Void> original) {
        if (!PlayerMessages.deathHeld((ServerPlayer) (Object) this)) original.call(connection, packet);
    }

    @Inject(method = "die", at = @At("RETURN"))
    private void hadron$flushDeath(DamageSource source, CallbackInfo ci) {
        PlayerMessages.flushDeath((ServerPlayer) (Object) this);
    }
}
