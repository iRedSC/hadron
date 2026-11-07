package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ItemSmeltEvent for furnaces, smokers and blast furnaces, once a smelt is certain to happen. A cancel makes vanilla
 * see "can't burn": nothing consumed or made, and cooking starts over.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class AbstractFurnaceBlockEntityMixin {
    @ModifyExpressionValue(method = "burn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;canBurn(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/item/crafting/RecipeHolder;Lnet/minecraft/core/NonNullList;ILnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;)Z"))
    @SuppressWarnings("unchecked")
    private static boolean hadron$smelt(boolean canBurn, @Local(argsOnly = true) RegistryAccess registries,
            @Local(argsOnly = true) RecipeHolder<?> recipe, @Local(argsOnly = true) NonNullList<ItemStack> items,
            @Local(argsOnly = true) AbstractFurnaceBlockEntity furnace) {
        if (!canBurn || !Hooks.listens(ItemSmeltEvent.class)) return canBurn;
        ItemStack input = items.get(0);
        ItemStack output = ((Recipe<SingleRecipeInput>) recipe.value()).assemble(new SingleRecipeInput(input),
                registries);
        return !ItemHooks.smelt(furnace.getLevel(), furnace.getBlockPos(), List.of(input.copyWithCount(1)),
                List.of(output), recipe);
    }
}
