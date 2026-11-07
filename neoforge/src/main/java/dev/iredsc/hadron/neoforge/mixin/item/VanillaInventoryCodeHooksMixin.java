package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.VanillaInventoryCodeHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge's hopper and dropper code for item handlers, which is the path hoppers take for nearly every container,
 * vanilla's included, since NeoForge gives them all an item handler. InventoryMoveItemEvent at the commit point of
 * each transfer, never at the simulations NeoForge runs first.
 */
@Mixin(VanillaInventoryCodeHooks.class)
abstract class VanillaInventoryCodeHooksMixin {
    @Shadow
    private static Optional<Pair<IItemHandler, Object>> getSourceItemHandler(Level level, Hopper hopper) {
        throw new AssertionError("mixin shadow");
    }

    /**
     * Hoppers and droppers pushing into an item handler. Returning the whole stack as the leftover makes both callers
     * restore the source exactly. A crafter's output is part of its ItemCraftEvent, so it fires nothing here.
     */
    @Inject(method = "putStackInInventoryAllSlots", at = @At("HEAD"), cancellable = true)
    private static void hadron$push(BlockEntity source, Object destination, IItemHandler handler, ItemStack stack,
            CallbackInfoReturnable<ItemStack> cir) {
        if (source instanceof CrafterBlockEntity || !Hooks.listens(InventoryMoveItemEvent.class)) return;
        InventoryHolder from = InventoryHolders.ofOwner(source);
        InventoryHolder to = InventoryHolders.ofOwner(destination);
        if (ItemHooks.moveHandler(from, InventoryChangeEvent.Change.ANY_SLOT, stack, to, handler, stack, from,
                ItemHooks.machine(source.getLevel(), source.getBlockPos()))) {
            cir.setReturnValue(stack);
        }
    }

    /**
     * Hoppers and hopper minecarts pulling from an item handler. NeoForge's version extracts inside a lambda with no
     * place to stop between finding the slot and moving the item, so while a plugin listens this runs the same steps
     * with the event in between; otherwise NeoForge's own code runs untouched.
     */
    @Inject(method = "extractHook", at = @At("HEAD"), cancellable = true)
    private static void hadron$pull(Level level, Hopper dest, CallbackInfoReturnable<Boolean> cir) {
        if (!Hooks.listens(InventoryMoveItemEvent.class)) return;
        Optional<Pair<IItemHandler, Object>> found = getSourceItemHandler(level, dest);
        if (found.isEmpty()) {
            cir.setReturnValue(null); // NeoForge's "no handler here": vanilla's code takes over
            return;
        }
        IItemHandler handler = found.get().getKey();
        InventoryHolder from = InventoryHolders.ofOwner(found.get().getValue());
        InventoryHolder to = InventoryHolders.of(dest, null);
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack extractItem = handler.extractItem(i, 1, true);
            if (extractItem.isEmpty()) continue;
            for (int j = 0; j < dest.getContainerSize(); j++) {
                ItemStack destStack = dest.getItem(j);
                if (dest.canPlaceItem(j, extractItem) && (destStack.isEmpty()
                        || destStack.getCount() < destStack.getMaxStackSize()
                        && destStack.getCount() < dest.getMaxStackSize()
                        && ItemStack.isSameItemSameComponents(extractItem, destStack))) {
                    if (ItemHooks.moveHandler(from, i, handler.getStackInSlot(i).copy(), to, null, extractItem, to,
                            ItemHooks.container(dest))) {
                        cir.setReturnValue(false);
                        return;
                    }
                    extractItem = handler.extractItem(i, 1, false);
                    if (destStack.isEmpty()) {
                        dest.setItem(j, extractItem);
                    } else {
                        destStack.grow(1);
                        dest.setItem(j, destStack);
                    }
                    dest.setChanged();
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
        cir.setReturnValue(false);
    }
}
