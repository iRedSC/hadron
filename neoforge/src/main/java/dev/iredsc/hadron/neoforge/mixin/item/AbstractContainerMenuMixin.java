package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
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

    /** Each take of a shift-click batch is its own craft or trade; a cancelled one ends the batch. */
    @WrapOperation(method = "doClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack hadron$takeOnce(AbstractContainerMenu menu, Player player, int slotId,
            Operation<ItemStack> original) {
        if (player instanceof ServerPlayer server && ItemHooks.cancelQuickTake(menu, slotId, server)) {
            return ItemStack.EMPTY;
        }
        return original.call(menu, player, slotId);
    }

    /**
     * Throwing the cursor out of the window drops the cursor's own stack, then empties the cursor. A refused throw
     * that the inventory has no room for is kept for that emptying, which puts it back on the cursor instead.
     */
    @Inject(method = "doClick", at = @At("HEAD"))
    private void hadron$clickStarts(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) ItemHooks.clickStarts((AbstractContainerMenu) (Object) this);
    }

    @Inject(method = "doClick", at = @At("RETURN"))
    private void hadron$clickEnds(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) ItemHooks.clickEnds((AbstractContainerMenu) (Object) this);
    }

    @WrapOperation(method = "doClick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;setCarried(Lnet/minecraft/world/item/ItemStack;)V"))
    private void hadron$keepCursor(AbstractContainerMenu menu, ItemStack stack, Operation<Void> original) {
        original.call(menu, ItemHooks.carried(menu, stack));
    }
}
