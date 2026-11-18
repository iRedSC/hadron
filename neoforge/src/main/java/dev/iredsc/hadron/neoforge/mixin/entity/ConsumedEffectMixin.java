package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.LastingActs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.OminousBottleItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SuspiciousStewItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The effects of what an entity drinks or eats are its own: vanilla applies them with no source, so the one consuming
 * is put on the stack as their explicit cause, around each application only ({@link LastingActs#asOwnCause}). Food's
 * own effects are applied in {@code LivingEntity} ({@link LivingEntityEffectMixin}).
 */
@Mixin({PotionItem.class, OminousBottleItem.class, SuspiciousStewItem.class})
abstract class ConsumedEffectMixin {
    /** A potion applies its effects in a lambda; the bottle and the stew in {@code finishUsingItem} itself. */
    @WrapOperation(method = {"finishUsingItem", "lambda$finishUsingItem$0"}, at = @At(value = "INVOKE", target = "Lnet/"
            + "minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
    private static boolean hadron$own(LivingEntity user, MobEffectInstance effect, Operation<Boolean> original) {
        if (!(user.level() instanceof ServerLevel)) return original.call(user, effect);
        return LastingActs.asOwnCause(user, () -> original.call(user, effect));
    }
}
