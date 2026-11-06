package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Taking a result a plugin offered where no recipe matches uses up one of each ingredient, instead of none. */
@Mixin(ResultSlot.class)
abstract class ResultSlotMixin {
    @WrapOperation(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRemainingItemsFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Lnet/minecraft/core/NonNullList;"))
    private NonNullList<ItemStack> hadron$leftovers(RecipeManager recipes, RecipeType<?> type, RecipeInput input,
            Level level, Operation<NonNullList<ItemStack>> original) {
        NonNullList<ItemStack> vanilla = original.call(recipes, type, input, level);
        return input instanceof CraftingInput craft ? ItemHooks.craftLeftovers(level, craft, vanilla) : vanilla;
    }
}
