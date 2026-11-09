package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every right-click that moves an item on or off a stand goes through {@code swapItem}; asked before it changes. */
@Mixin(ArmorStand.class)
abstract class ArmorStandMixin {
    @Shadow
    private int disabledSlots;

    @Inject(method = "swapItem", at = @At("HEAD"), cancellable = true)
    private void hadron$swap(Player player, EquipmentSlot slot, ItemStack hand, InteractionHand which,
            CallbackInfoReturnable<Boolean> cir) {
        if (EntityHandling.refuseStandSwap((ArmorStand) (Object) this, disabledSlots, player, slot, hand, which)) {
            cir.setReturnValue(false);
        }
    }
}
