package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * EquipEvent for a mob picking up gear. Asked at the last check before it equips, before it drops what it held, so a
 * refusal changes nothing.
 */
@Mixin(Mob.class)
abstract class MobEquipMixin {
    @WrapOperation(method = "equipItemIfPossible", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;canHoldItem(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean hadron$equip(Mob mob, ItemStack stack, Operation<Boolean> original, @Local EquipmentSlot slot) {
        return original.call(mob, stack) && !ItemHooks.cancelEquip(mob, slot, slot.limit(stack.copy()));
    }
}
