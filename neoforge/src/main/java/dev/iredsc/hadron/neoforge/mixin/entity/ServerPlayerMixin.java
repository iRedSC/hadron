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
        EntityTeleports.Destination to = EntityTeleports.ask(self, null, x, y, z, self.getYRot(), self.getXRot());
        if (to == null) return;
        if (to.differs(self, null, x, y, z, self.getYRot(), self.getXRot())) {
            EntityTeleports.redirect(self, to);
            return;
        }
        EntityTeleports.during(self, () -> original.call(x, y, z));
    }

    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")
    private boolean hadron$playerTeleportTo(ServerLevel level, double x, double y, double z,
            Set<RelativeMovement> relative, float yRot, float xRot, Operation<Boolean> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        EntityTeleports.Destination to = EntityTeleports.ask(self, level, x, y, z, yRot, xRot);
        if (to == null) return false;
        return EntityTeleports.during(self, () -> original.call(to.level(), to.x(), to.y(), to.z(), relative,
                to.yRot(), to.xRot()));
    }

    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V")
    private void hadron$playerTeleportToLevel(ServerLevel level, double x, double y, double z, float yaw,
            float pitch, Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        EntityTeleports.Destination to = EntityTeleports.ask(self, level, x, y, z, yaw, pitch);
        if (to == null) return;
        EntityTeleports.during(self, () -> original.call(to.level(), to.x(), to.y(), to.z(), to.yRot(), to.xRot()));
    }

    @WrapMethod(method = "changeDimension")
    private Entity hadron$playerChangeDimension(DimensionTransition transition, Operation<Entity> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        var pos = transition.pos();
        EntityTeleports.Destination to = EntityTeleports.ask(self, transition.newLevel(), pos.x, pos.y, pos.z,
                transition.yRot(), transition.xRot());
        if (to == null) return null;
        DimensionTransition going = to.differs(self, transition.newLevel(), pos.x, pos.y, pos.z, transition.yRot(),
                transition.xRot())
                ? new DimensionTransition(to.level(), to.position(), transition.speed(), to.yRot(), to.xRot(),
                        transition.missingRespawnBlock(), transition.postDimensionTransition())
                : transition;
        return EntityTeleports.during(self, () -> original.call(going));
    }
}
