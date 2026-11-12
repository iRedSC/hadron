package dev.iredsc.hadron.neoforge.mixin.net;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.iredsc.hadron.neoforge.EntityMoves;
import dev.iredsc.hadron.neoforge.NetHooks;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A click or hit on an entity the player can't see is ignored, as if it weren't there: a hacked client knows ids. A
 * move a client sends (walking, steering its vehicle) is asked about where it is applied, before anything is broadcast.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;
    @Shadow private double vehicleLastGoodX;
    @Shadow private double vehicleLastGoodY;
    @Shadow private double vehicleLastGoodZ;

    @WrapMethod(method = "handleMovePlayer")
    private void hadron$walk(ServerboundMovePlayerPacket packet, Operation<Void> original) {
        // Vanilla first moves the packet onto the server thread (by throwing); only there is it applied and checked.
        if (!player.server.isSameThread()) {
            original.call(packet);
            return;
        }
        EntityMoves.walked(player, () -> original.call(packet));
    }

    @WrapMethod(method = "handleMoveVehicle")
    private void hadron$steer(ServerboundMoveVehiclePacket packet, Operation<Void> original) {
        if (!player.server.isSameThread()) {
            original.call(packet);
            return;
        }
        EntityMoves.steered(player, () -> original.call(packet), (x, y, z) -> {
            vehicleLastGoodX = x;
            vehicleLastGoodY = y;
            vehicleLastGoodZ = z;
        });
    }

    @WrapOperation(method = "handleInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/"
            + "game/ServerboundInteractPacket;getTarget(Lnet/minecraft/server/level/ServerLevel;)"
            + "Lnet/minecraft/world/entity/Entity;"))
    private @Nullable Entity hadron$hidden(ServerboundInteractPacket packet, ServerLevel level, Operation<Entity> original) {
        Entity target = original.call(packet, level);
        return target == null || NetHooks.canSee(player, target) ? target : null;
    }
}
