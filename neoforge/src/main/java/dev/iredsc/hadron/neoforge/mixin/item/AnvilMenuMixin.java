package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.api.event.item.PrepareAnvilEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PrepareAnvilEvent: after the anvil (or a mod, through NeoForge's AnvilUpdateEvent) worked out its offer. Only an
 * offer; nothing is spent until the result is taken, which the click hook reports.
 */
@Mixin(AnvilMenu.class)
abstract class AnvilMenuMixin {
    @Shadow
    private String itemName;

    @Shadow
    @Final
    private DataSlot cost;

    @Inject(method = "createResult", at = @At("RETURN"))
    private void hadron$prepare(CallbackInfo ci) {
        ItemCombinerMenuAccessor self = (ItemCombinerMenuAccessor) this;
        if (!(self.hadron$player() instanceof ServerPlayer viewer) || !Hooks.listens(PrepareAnvilEvent.class)) return;
        Container inputs = self.hadron$inputSlots();
        ItemStack left = inputs.getItem(0);
        if (left.isEmpty()) return;
        AnvilMenu menu = (AnvilMenu) (Object) this;
        PrepareAnvilEvent event = Hooks.post(new PrepareAnvilEvent(viewer.serverLevel(), viewer,
                InventoryHolders.ofMenu(menu, viewer), menu, left, inputs.getItem(1), itemName,
                self.hadron$resultSlots().getItem(0), cost.get(), ItemHooks.actor(viewer)));
        if (event.isCancelled()) {
            self.hadron$resultSlots().setItem(0, ItemStack.EMPTY);
            cost.set(0);
        } else {
            self.hadron$resultSlots().setItem(0, event.getResult());
            cost.set(event.getRepairCost());
        }
        menu.broadcastChanges();
    }
}
