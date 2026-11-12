package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ChorusFruitItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla takes a chorus fruit eater off what it rides before trying each spot; it waits until a landing goes ahead,
 * so an eater whose every landing is refused stays riding.
 */
@Mixin(ChorusFruitItem.class)
abstract class ChorusFruitItemMixin {
    @WrapMethod(method = "finishUsingItem")
    private ItemStack hadron$finishUsingItem(ItemStack stack, Level level, LivingEntity eater,
            Operation<ItemStack> original) {
        return EntityTeleports.leavingOnLanding(() -> original.call(stack, level, eater));
    }

    @WrapOperation(method = "finishUsingItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;stopRiding()V"))
    private void hadron$stopRidingOnLanding(LivingEntity eater, Operation<Void> original) {
        if (!EntityTeleports.leaveOnLanding(eater, () -> original.call(eater))) original.call(eater);
    }
}
