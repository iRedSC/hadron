package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntityDeaths;
import dev.iredsc.hadron.neoforge.EntitySpawnRefunds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the death loot, so the death event can decide drops and experience together; and refunds a released bow
 * or trident whose every spawn was refused.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
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
}
