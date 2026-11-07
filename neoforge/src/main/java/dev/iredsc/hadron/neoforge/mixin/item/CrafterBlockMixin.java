package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The crafter block when powered: PrepareItemCraftEvent once it has worked out its result (emptied, it clicks and
 * crafts nothing), then ItemCraftEvent before it consumes or makes anything.
 */
@Mixin(CrafterBlock.class)
abstract class CrafterBlockMixin {
    /**
     * The result, just assembled, at its first read: the crafter's empty check. Injecting at the isEmpty call itself
     * would run after the original was loaded for it, so an emptied result would still pass the check and be crafted.
     */
    @ModifyVariable(method = "dispenseFrom", ordinal = 0, at = @At(value = "LOAD", ordinal = 0))
    private ItemStack hadron$prepare(ItemStack result, BlockState state, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CrafterBlockEntity crafter)) return result;
        RecipeHolder<CraftingRecipe> recipe = CrafterBlock.getPotentialResults(level, crafter.asCraftInput())
                .orElse(null);
        return ItemHooks.prepareCraft(level, new InventoryHolder.Block(level, pos), null, null, crafter.getItems(),
                recipe, result, ItemHooks.machine(level, pos));
    }

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
