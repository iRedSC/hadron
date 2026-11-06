package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityDeaths;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets the death loot, so the death event can decide drops and experience together. */
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
}
