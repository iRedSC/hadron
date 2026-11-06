package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemEnchantEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ItemEnchantEvent for the enchanting table. Fired before anything is spent, after the same checks vanilla makes, with
 * the enchantments vanilla will apply (the list is seeded, so asking for it twice gives the same one).
 */
@Mixin(EnchantmentMenu.class)
abstract class EnchantmentMenuMixin {
    @Shadow
    @Final
    private Container enchantSlots;

    @Shadow
    @Final
    public int[] costs;

    @Shadow
    private List<EnchantmentInstance> getEnchantmentList(RegistryAccess registryAccess, ItemStack stack, int slot,
            int cost) {
        throw new AssertionError("mixin shadow");
    }

    @Inject(method = "clickMenuButton", at = @At("HEAD"), cancellable = true)
    private void hadron$enchant(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        if (!(player instanceof ServerPlayer server) || !Hooks.listens(ItemEnchantEvent.class)) return;
        if (id < 0 || id >= costs.length) return;
        ItemStack item = enchantSlots.getItem(0);
        ItemStack lapis = enchantSlots.getItem(1);
        int levels = id + 1;
        if ((lapis.isEmpty() || lapis.getCount() < levels) && !player.hasInfiniteMaterials()) return;
        if (costs[id] <= 0 || item.isEmpty()
                || (player.experienceLevel < levels || player.experienceLevel < costs[id])
                && !player.getAbilities().instabuild) return;
        List<EnchantmentInstance> list = getEnchantmentList(server.level().registryAccess(), item, id, costs[id]);
        if (list.isEmpty()) return;
        ItemStack after = item.getItem().applyEnchantments(item.copy(), list);
        EnchantmentMenu menu = (EnchantmentMenu) (Object) this;
        InventoryHolder holder = InventoryHolders.ofMenu(menu, server);
        if (Hooks.post(new ItemEnchantEvent(server.serverLevel(), holder, item, after, levels, ItemHooks.actor(server)))
                .isCancelled()) {
            cir.setReturnValue(false);
        }
    }
}
