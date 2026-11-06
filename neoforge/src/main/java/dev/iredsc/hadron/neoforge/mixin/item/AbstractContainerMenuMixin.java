package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * InventoryClickEvent, and the craft, trade or item change a click on a result slot makes. Before anything moves; a
 * real player's client is corrected by the packet handler, which compares what it predicted with what happened.
 */
@Mixin(AbstractContainerMenu.class)
abstract class AbstractContainerMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void hadron$click(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer server
                && ItemHooks.click((AbstractContainerMenu) (Object) this, slotId, button, type, server)) {
            ci.cancel();
        }
    }
}
