package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ItemCraftEvent for the crafter block, after it found a recipe and before it consumes or makes anything. */
@Mixin(CrafterBlock.class)
abstract class CrafterBlockMixin {
    @Inject(method = "dispenseFrom", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/CrafterBlockEntity;setCraftingTicksRemaining(I)V"))
    private void hadron$craft(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci,
            @Local CrafterBlockEntity crafter, @Local CraftingInput input,
            @Local RecipeHolder<CraftingRecipe> recipe, @Local(ordinal = 0) ItemStack result) {
        List<ItemStack> outputs = new ArrayList<>();
        outputs.add(result);
        outputs.addAll(recipe.value().getRemainingItems(input));
        if (ItemHooks.crafterCraft(level, pos, crafter.getItems(), outputs, recipe)) {
            level.levelEvent(1050, pos, 0); // the crafter's "nothing to craft" click
            ci.cancel();
        }
    }
}
