package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ItemMergeEvent: both merge calls in tryToMerge, reached only when the two really can merge. */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @WrapOperation(method = "tryToMerge", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/ItemEntity;merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V"))
    private void hadron$merge(ItemEntity destination, ItemStack destinationStack, ItemEntity origin,
            ItemStack originStack, Operation<Void> original) {
        if (ItemHooks.merge(destination, origin)) return;
        original.call(destination, destinationStack, origin, originStack);
    }
}
