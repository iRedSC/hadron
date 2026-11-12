package dev.iredsc.hadron.neoforge.mixin.entity;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import dev.iredsc.hadron.neoforge.HeldEffects;
import dev.iredsc.hadron.neoforge.PlayerEvents;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ServerPlayer overrides every teleport method without calling Entity's, so each is wrapped again here. A statistic
 * counted while a refusal is still to come waits for it ({@link HeldEffects}).
 */
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
    private @Nullable Entity hadron$playerChangeDimension(DimensionTransition transition, Operation<Entity> original) {
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
        // Where the player stands before leaving: the event fired once they arrive states it.
        ServerLevel fromLevel = self.serverLevel();
        Vec3 fromPosition = self.position();
        return EntityTeleports.during(self, () -> {
            Entity arrived = original.call(going);
            if (arrived == self && self.serverLevel() != fromLevel) {
                PlayerEvents.changedWorld(self, fromLevel, fromPosition);
            }
            return arrived;
        });
    }

    /** Counting a statistic: inside a hold it waits whole, and comes back through here when kept. */
    @WrapWithCondition(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/stats/ServerStatsCounter;increment(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/stats/Stat;I)V"))
    private boolean hadron$holdStat(ServerStatsCounter stats, Player player, Stat<?> stat, int amount) {
        if (!HeldEffects.holding()) return true;
        ServerPlayer self = (ServerPlayer) (Object) this;
        HeldEffects.hold(() -> self.awardStat(stat, amount));
        return false;
    }

    /** The scoreboard objectives counting it, which wait with it. */
    @WrapWithCondition(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/scores/Scoreboard;forAllObjectives(Lnet/minecraft/world/scores/criteria/ObjectiveCriteria;Lnet/minecraft/world/scores/ScoreHolder;Ljava/util/function/Consumer;)V"))
    private boolean hadron$holdStatScore(Scoreboard scoreboard, ObjectiveCriteria criteria, ScoreHolder holder,
            Consumer<ScoreAccess> action) {
        return !HeldEffects.holding();
    }
}
