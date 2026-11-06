package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ItemPickupEvent for mobs. The one call site in Mob.aiStep reaches every override (allays, foxes, piglins,
 * villagers), after vanilla has checked the mob wants the item.
 */
@Mixin(Mob.class)
abstract class MobMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;pickUpItem(Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void hadron$pickUp(Mob mob, ItemEntity item, Operation<Void> original) {
        if (ItemHooks.mobPickup(mob, item)) return;
        original.call(mob, item);
    }
}
