package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** EquipEvent for right-clicking armour on, and for a dispenser putting it on someone. */
@Mixin(ArmorItem.class)
abstract class ArmorItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void hadron$equip(Level level, Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (!level.isClientSide && ItemHooks.cancelRightClickEquip(player, hand)) {
            cir.setReturnValue(InteractionResultHolder.fail(player.getItemInHand(hand)));
        }
    }

    /** Before the armour leaves the dispenser: refused, it stays inside rather than being thrown out instead. */
    @Inject(method = "dispenseArmor", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;split(I)Lnet/minecraft/world/item/ItemStack;"))
    private static void hadron$dispenseEquip(BlockSource source, ItemStack armor, CallbackInfoReturnable<Boolean> cir,
            @Local LivingEntity entity, @Local EquipmentSlot slot) {
        if (ItemHooks.cancelEquip(entity, slot, armor.copyWithCount(1))) cir.setReturnValue(true);
    }
}
