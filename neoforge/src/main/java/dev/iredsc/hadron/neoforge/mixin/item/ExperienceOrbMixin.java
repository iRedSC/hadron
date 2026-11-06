package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ItemRepairEvent for Mending. Cancelled, the item stays damaged and the orb's experience goes to the player instead
 * of being spent on a repair that didn't happen.
 */
@Mixin(ExperienceOrb.class)
abstract class ExperienceOrbMixin {
    @Unique
    private boolean hadron$refund;

    /**
     * The durability Mending is about to restore. Returning 0 makes vanilla repair nothing and stop, so it neither
     * changes the item nor goes on to spend the rest of the orb.
     */
    @WrapOperation(method = "repairPlayerItems", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int hadron$mend(int available, int damage, Operation<Integer> original, @Local ItemStack stack,
            @Local(argsOnly = true) ServerPlayer player) {
        int repair = original.call(available, damage);
        if (repair > 0 && ItemHooks.mend(player, stack, stack.getDamageValue() - repair)) {
            hadron$refund = true;
            return 0;
        }
        return repair;
    }

    /** Vanilla returns 0 ("all spent") after a repair of 0; a cancelled repair returns all of it instead. */
    @ModifyReturnValue(method = "repairPlayerItems", at = @At("RETURN"))
    private int hadron$refund(int left, @Local(argsOnly = true) int value) {
        if (!hadron$refund) return left;
        hadron$refund = false;
        return value;
    }
}
