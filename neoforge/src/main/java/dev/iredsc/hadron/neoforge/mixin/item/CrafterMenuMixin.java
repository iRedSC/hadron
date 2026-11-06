package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CrafterBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * PrepareItemCraftEvent for the result a crafter shows a player looking in, so it matches what the crafter makes
 * when powered (asked again then, in {@code CrafterBlockMixin}).
 */
@Mixin(CrafterMenu.class)
abstract class CrafterMenuMixin {
    @Shadow
    @Final
    private Player player;

    @Shadow
    @Final
    private CraftingContainer container;

    @ModifyArg(method = "refreshRecipeResult", index = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private ItemStack hadron$preview(ItemStack result) {
        if (!(player instanceof ServerPlayer viewer)) return result;
        CrafterMenu menu = (CrafterMenu) (Object) this;
        var recipe = CrafterBlock.getPotentialResults(viewer.level(), container.asCraftInput()).orElse(null);
        return ItemHooks.prepareCraft(viewer.serverLevel(), InventoryHolders.ofMenu(menu, viewer), menu, viewer,
                container.getItems(), recipe, result, ItemHooks.actor(viewer));
    }
}
