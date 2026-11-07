package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** EquipEvent for right-clicking an elytra on. */
@Mixin(ElytraItem.class)
abstract class ElytraItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void hadron$equip(Level level, Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (!level.isClientSide && ItemHooks.cancelRightClickEquip(player, hand)) {
            cir.setReturnValue(InteractionResultHolder.fail(player.getItemInHand(hand)));
        }
    }
}
