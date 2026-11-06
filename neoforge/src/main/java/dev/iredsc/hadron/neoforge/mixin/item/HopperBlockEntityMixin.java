package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hoppers and hopper minecarts between vanilla containers (NeoForge's item handler path is in
 * {@link VanillaInventoryCodeHooksMixin}): InventoryMoveItemEvent for pushing and pulling, ItemPickupEvent for item
 * entities. Each fires only when the move would really happen; a cancelled move returns the stack as the leftover, so
 * vanilla puts it back where it was.
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
    private static final String ADD_ITEM = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;addItem("
            + "Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;";

    /** Pushing: the hopper's slot {@code slot} has already given up {@code stack}. */
    @WrapOperation(method = "ejectItems", at = @At(value = "INVOKE", target = ADD_ITEM))
    private static ItemStack hadron$push(Container hopper, Container destination, ItemStack stack, Direction face,
            Operation<ItemStack> original, @Local(ordinal = 0) int slot, @Local(ordinal = 0) ItemStack inSlot) {
        int before = inSlot.getCount() + stack.getCount();
        if (ItemHooks.move(hopper, slot, before, destination, stack, face, hopper)) return stack;
        return original.call(hopper, destination, stack, face);
    }

    /** Pulling: the source's slot has already given up {@code stack}. */
    @WrapOperation(method = "tryTakeInItemFromSlot", at = @At(value = "INVOKE", target = ADD_ITEM))
    private static ItemStack hadron$pull(Container source, Container hopper, ItemStack stack, Direction face,
            Operation<ItemStack> original, @Local(argsOnly = true) int slot, @Local(ordinal = 0) ItemStack inSlot) {
        int before = inSlot.getCount() + stack.getCount();
        if (ItemHooks.move(source, slot, before, hopper, stack, face, hopper)) return stack;
        return original.call(source, hopper, stack, face);
    }

    /** A hopper or hopper minecart sucking up an item entity. */
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void hadron$pickup(Container hopper, ItemEntity item, CallbackInfoReturnable<Boolean> cir) {
        if (ItemHooks.hopperPickup(hopper, item)) cir.setReturnValue(false);
    }
}
