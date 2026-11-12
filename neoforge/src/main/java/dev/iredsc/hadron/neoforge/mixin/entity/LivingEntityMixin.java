package dev.iredsc.hadron.neoforge.mixin.entity;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.EntityDeaths;
import dev.iredsc.hadron.neoforge.EntityDismounts;
import dev.iredsc.hadron.neoforge.EntitySpawnRefunds;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the death loot, so the death event can decide drops and experience together; refunds a released bow or
 * trident whose every spawn was refused; remembers the hit in progress; asks about a random teleport where it lands;
 * and sets a rider down where its dismount said.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin implements EntityActors.Hurting {
    /**
     * A sleeper getting up frees their bed: in the morning, on leaving the game, or woken. Vanilla's own, never an
     * outsider's in a watched area, whoever or whatever is on the stack.
     */
    @WrapMethod(method = "stopSleeping")
    private void hadron$getUp(Operation<Void> original) {
        Level level = ((LivingEntity) (Object) this).level();
        AreaIndex.enterVanillaWork(level);
        try {
            original.call();
        } finally {
            AreaIndex.exitVanillaWork(level);
        }
    }

    @Unique private @Nullable DamageSource hadron$hurting;

    @Override
    public @Nullable DamageSource hadron$hurting() {
        return hadron$hurting;
    }

    /** Remembers the hit in progress, so its knockback is blamed on it rather than on the previous hit. */
    @WrapMethod(method = "hurt")
    private boolean hadron$hurt(DamageSource source, float amount, Operation<Boolean> original) {
        DamageSource outer = hadron$hurting;
        hadron$hurting = source;
        try {
            return original.call(source, amount);
        } finally {
            hadron$hurting = outer;
        }
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"))
    private void hadron$deathLootStart(ServerLevel level, DamageSource source, CallbackInfo ci) {
        EntityDeaths.begin((LivingEntity) (Object) this);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("RETURN"))
    private void hadron$deathLootEnd(ServerLevel level, DamageSource source, CallbackInfo ci) {
        EntityDeaths.end((LivingEntity) (Object) this, source);
    }

    @WrapMethod(method = "releaseUsingItem")
    private void hadron$refundRelease(Operation<Void> original) {
        if ((Object) this instanceof Player player) {
            EntitySpawnRefunds.releasingItem(player, original::call);
        } else {
            original.call();
        }
    }

    /**
     * A random teleport (an enderman, chorus fruit, a mod): vanilla's search for ground and room runs first, without
     * moving anything anyone sees, and the teleport is asked about with where it found.
     */
    @WrapMethod(method = "randomTeleport")
    private boolean hadron$randomTeleport(double x, double y, double z, boolean effects, Operation<Boolean> original) {
        return EntityTeleports.randomTeleport((LivingEntity) (Object) this, x, y, z, effects,
                (tx, ty, tz, fx) -> original.call(tx, ty, tz, fx));
    }

    /** While the search runs as a probe, it places the entity without a teleport: nothing is sent, nothing asked. */
    @WrapOperation(method = "randomTeleport", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;teleportTo(DDD)V"))
    private void hadron$probeTeleport(LivingEntity self, double x, double y, double z, Operation<Void> original) {
        if (EntityTeleports.probing(self)) {
            self.setPos(x, y, z);
        } else {
            original.call(self, x, y, z);
        }
    }

    /** A probe stops nothing: a mob's path is dropped once its teleport goes ahead. */
    @WrapWithCondition(method = "randomTeleport", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;stop()V"))
    private boolean hadron$probeKeepsPath(PathNavigation navigation) {
        return !EntityTeleports.probing((LivingEntity) (Object) this);
    }

    /** Getting off: the set-down is worked out for its event and applied exactly ({@link EntityDismounts}). */
    @WrapMethod(method = "stopRiding")
    private void hadron$stopRiding(Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            original.call();
            return;
        }
        EntityDismounts.stopRiding(self, original::call);
    }

    @WrapMethod(method = "dismountVehicle")
    private void hadron$dismountVehicle(Entity vehicle, Operation<Void> original) {
        if (!EntityDismounts.setDownAsDecided((LivingEntity) (Object) this, vehicle)) original.call(vehicle);
    }

    @Inject(method = "knockback", at = @At("HEAD"))
    private void hadron$knockbackStart(double strength, double x, double z, CallbackInfo ci) {
        dev.iredsc.hadron.neoforge.EntityVelocities.enterKnockback();
    }

    @Inject(method = "knockback", at = @At("RETURN"))
    private void hadron$knockbackEnd(double strength, double x, double z, CallbackInfo ci) {
        dev.iredsc.hadron.neoforge.EntityVelocities.exitKnockback();
    }
}
