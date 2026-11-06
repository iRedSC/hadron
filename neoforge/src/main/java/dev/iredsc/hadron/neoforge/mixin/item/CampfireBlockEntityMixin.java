package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ItemSmeltEvent for campfires, when a slot's item is done. A cancel makes vanilla skip popping it out, and its
 * cooking starts over.
 */
@Mixin(CampfireBlockEntity.class)
abstract class CampfireBlockEntityMixin {
    @ModifyExpressionValue(method = "cookTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;isItemEnabled(Lnet/minecraft/world/flag/FeatureFlagSet;)Z"))
    private static boolean hadron$cook(boolean enabled, @Local(argsOnly = true) Level level,
            @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) CampfireBlockEntity campfire,
            @Local(ordinal = 0) int slot, @Local(ordinal = 0) ItemStack input, @Local(ordinal = 1) ItemStack output) {
        if (!enabled || !Hooks.listens(ItemSmeltEvent.class)) return enabled;
        if (!ItemHooks.smelt(level, pos, List.of(input.copyWithCount(1)), List.of(output), null)) return true;
        ((CampfireBlockEntityAccessor) campfire).hadron$cookingProgress()[slot] = 0;
        return false;
    }
}
