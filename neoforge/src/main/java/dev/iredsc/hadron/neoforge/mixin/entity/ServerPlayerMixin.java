package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;

/** ServerPlayer overrides every teleport method without calling Entity's, so each is wrapped again here. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @WrapMethod(method = "teleportTo(DDD)V")
    private void hadron$playerTeleport(double x, double y, double z, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!EntityTeleports.ask(self, null, x, y, z)) return;
        EntityTeleports.during(self, () -> original.call(x, y, z));
    }

    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")
    private boolean hadron$playerTeleportTo(ServerLevel level, double x, double y, double z,
            Set<RelativeMovement> relative, float yRot, float xRot, Operation<Boolean> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!EntityTeleports.ask(self, level, x, y, z)) return false;
        return EntityTeleports.during(self, () -> original.call(level, x, y, z, relative, yRot, xRot));
    }

    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V")
    private void hadron$playerTeleportToLevel(ServerLevel level, double x, double y, double z, float yaw,
            float pitch, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!EntityTeleports.ask(self, level, x, y, z)) return;
        EntityTeleports.during(self, () -> original.call(level, x, y, z, yaw, pitch));
    }

    @WrapMethod(method = "changeDimension")
    private Entity hadron$playerChangeDimension(DimensionTransition transition, Operation<Entity> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        var to = transition.pos();
        if (!EntityTeleports.ask(self, transition.newLevel(), to.x, to.y, to.z)) return null;
        return EntityTeleports.during(self, () -> original.call(transition));
    }
}
