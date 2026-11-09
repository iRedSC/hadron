package dev.iredsc.hadron.neoforge.mixin.entity;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.EntityDeaths;
import dev.iredsc.hadron.neoforge.EntitySpawnRefunds;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
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
 * trident whose every spawn was refused; remembers the hit in progress; and makes a random teleport one teleport.
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
            EntitySpawnRefunds.using(player, () -> {
                original.call();
                return null;
            });
        } else {
            original.call();
        }
    }

    /** An enderman's or chorus fruit's teleport, with its step back when the spot doesn't fit: one teleport. */
    @WrapMethod(method = "randomTeleport")
    private boolean hadron$randomTeleport(double x, double y, double z, boolean effects, Operation<Boolean> original) {
        return EntityTeleports.randomTeleport((LivingEntity) (Object) this, x, y, z,
                (tx, ty, tz) -> original.call(tx, ty, tz, effects));
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
