package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DropperBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * InventoryMoveItemEvent for a dropper inserting into a vanilla container in front of it. Vanilla inserts a copy, so a
 * cancel returning it as the leftover leaves the dropper's slot untouched.
 */
@Mixin(DropperBlock.class)
abstract class DropperBlockMixin {
    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack hadron$insert(Container dropper, Container destination, ItemStack stack, Direction face,
            Operation<ItemStack> original, @Local(ordinal = 0) int slot, @Local(ordinal = 0) ItemStack inSlot) {
        if (ItemHooks.move(dropper, slot, inSlot.getCount(), destination, stack, face, dropper)) return stack;
        return original.call(dropper, destination, stack, face);
    }
}
