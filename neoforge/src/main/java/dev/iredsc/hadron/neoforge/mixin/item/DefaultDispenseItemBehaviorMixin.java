package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ItemDropEvent for dispensers and droppers spitting an item out. Before the split, so a cancel leaves the stack whole
 * in the dispenser. Subclasses that fall back to dropping call this too; ones that fire projectiles don't.
 */
@Mixin(DefaultDispenseItemBehavior.class)
abstract class DefaultDispenseItemBehaviorMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void hadron$drop(BlockSource source, ItemStack item, CallbackInfoReturnable<ItemStack> cir) {
        if (ItemHooks.dispense(source, item)) cir.setReturnValue(item);
    }
}
