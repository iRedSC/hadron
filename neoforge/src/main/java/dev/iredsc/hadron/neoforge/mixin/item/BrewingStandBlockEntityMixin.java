package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ItemSmeltEvent for brewing stands, before the brew: the ingredient and bottles are the inputs, the brewed bottles the
 * outputs. Cancelled, nothing is consumed and the stand brews again after its next brew time.
 */
@Mixin(BrewingStandBlockEntity.class)
abstract class BrewingStandBlockEntityMixin {
    @Inject(method = "doBrew", at = @At("HEAD"), cancellable = true)
    private static void hadron$brew(Level level, BlockPos pos, NonNullList<ItemStack> items, CallbackInfo ci) {
        if (!Hooks.listens(ItemSmeltEvent.class)) return;
        ItemStack ingredient = items.get(3);
        PotionBrewing brewing = level.potionBrewing();
        List<ItemStack> inputs = new ArrayList<>();
        List<ItemStack> outputs = new ArrayList<>();
        inputs.add(ingredient.copyWithCount(1));
        for (int i = 0; i < 3; i++) {
            ItemStack bottle = items.get(i);
            if (bottle.isEmpty()) continue;
            inputs.add(bottle.copy());
            outputs.add(brewing.mix(ingredient, bottle.copy()));
        }
        if (ItemHooks.smelt(level, pos, inputs, outputs, null)) ci.cancel();
    }
}
