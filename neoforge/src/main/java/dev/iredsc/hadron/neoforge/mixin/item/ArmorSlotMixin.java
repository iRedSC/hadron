package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** EquipEvent for clicking or shift-clicking into an armour slot: a refused item isn't accepted there. */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
abstract class ArmorSlotMixin {
    @Shadow @Final private LivingEntity owner;
    @Shadow @Final private EquipmentSlot slot;

    @ModifyReturnValue(method = "mayPlace", at = @At("RETURN"))
    private boolean hadron$equip(boolean accepts, ItemStack stack) {
        return accepts && !(owner.level().isClientSide ? false : ItemHooks.cancelSlotEquip(this, owner, slot, stack));
    }
}
