package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** InventoryOpenEvent: every menu a server player (or fake player) opens, and horse inventories. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    /**
     * Right after the menu is built and before anything is sent to the client. Cancelled, the menu is closed again
     * (a chest's lid shuts, its viewer count drops) and openMenu goes on as if nothing could be opened.
     */
    @WrapOperation(
            method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/MenuProvider;createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/inventory/AbstractContainerMenu;"))
    private AbstractContainerMenu hadron$open(MenuProvider provider, int id, Inventory inventory, Player player,
            Operation<AbstractContainerMenu> original) {
        AbstractContainerMenu menu = original.call(provider, id, inventory, player);
        if (menu == null) return null;
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (ItemHooks.open(self, InventoryHolders.ofMenu(menu, self), menu)) {
            menu.removed(self);
            return null;
        }
        return menu;
    }

    /** Horse, donkey and llama inventories open without a MenuProvider; the menu is built after this. */
    @Inject(method = "openHorseInventory", at = @At("HEAD"), cancellable = true)
    private void hadron$openHorse(AbstractHorse horse, Container inventory, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (ItemHooks.open(self, new InventoryHolder.Entity(horse), null)) ci.cancel();
    }
}
