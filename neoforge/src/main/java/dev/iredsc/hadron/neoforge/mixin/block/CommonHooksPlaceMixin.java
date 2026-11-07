package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A cancelled placement keeps the item as it was, and an allowed one keeps what the use did to it. NeoForge reads the
 * stack's component map before the use and again after it, but both are the one live map, so putting the "before"
 * back for the event also rewrote the "after": a cancel kept the lost durability. Both reads are now copies.
 */
@Mixin(value = CommonHooks.class, remap = false)
abstract class CommonHooksPlaceMixin {
    @ModifyExpressionValue(method = "onPlaceItemIntoWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getComponents()Lnet/minecraft/core/component/DataComponentMap;"))
    private static DataComponentMap hadron$snapshot(DataComponentMap live) {
        // The map itself, copied: a local would be ambiguous here, since NeoForge also keeps a pre-use copy stack.
        return live instanceof PatchedDataComponentMap patched ? patched.copy() : live;
    }
}
