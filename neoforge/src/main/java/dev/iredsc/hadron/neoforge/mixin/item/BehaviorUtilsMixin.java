package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mobs throwing items: an ItemDropEvent before the item entity exists, so a cancel can keep it in the mob. */
@Mixin(BehaviorUtils.class)
abstract class BehaviorUtilsMixin {
    @Inject(method = "throwItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;F)V", at = @At("HEAD"), cancellable = true)
    private static void hadron$throw(LivingEntity entity, ItemStack stack, Vec3 offset, Vec3 speed, float yOffset,
            CallbackInfo ci) {
        if (ItemHooks.cancelMobThrow(entity, stack)) ci.cancel();
    }
}
