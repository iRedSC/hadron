package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * PrepareItemCraftEvent for every grid built on vanilla's crafting grid: the player's own 2x2 (InventoryMenu calls
 * this too), the crafting table, and mods' menus that reuse it. The result is swapped before it is stored, shown to
 * the remote slot and sent, so all three agree.
 */
@Mixin(CraftingMenu.class)
abstract class CraftingMenuMixin {
    @ModifyVariable(method = "slotChangedCraftingGrid", ordinal = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private static ItemStack hadron$prepare(ItemStack result, AbstractContainerMenu menu, Level level, Player player,
            CraftingContainer craftSlots, ResultContainer resultSlots, RecipeHolder<CraftingRecipe> recipe) {
        if (!(player instanceof ServerPlayer viewer) || !(level instanceof ServerLevel server)) return result;
        // The recipe used is only set when one matched; with no result it may be a stale one from before.
        RecipeHolder<?> matched = result.isEmpty() ? null : resultSlots.getRecipeUsed();
        return ItemHooks.prepareCraft(server, InventoryHolders.ofMenu(menu, viewer), menu, viewer,
                craftSlots.getItems(), matched, result, ItemHooks.actor(viewer));
    }
}
