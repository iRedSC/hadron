package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.EntityActors;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hanging an item frame or painting: the player is the actor, and a refused placement doesn't spend the item. */
@Mixin(HangingEntityItem.class)
abstract class HangingEntityItemMixin {
    @WrapOperation(method = "useOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean hadron$hang(Level level, Entity entity, Operation<Boolean> original,
            @Local(argsOnly = true) UseOnContext context, @Share("refused") LocalBooleanRef refused) {
        boolean added;
        if (context.getPlayer() instanceof ServerPlayer player) {
            try (Actors.Scope scope = Actors.push(EntityActors.of(player))) {
                added = original.call(level, entity);
            }
        } else {
            added = original.call(level, entity);
        }
        refused.set(!added);
        return added;
    }

    @WrapOperation(method = "useOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void hadron$spend(ItemStack stack, int amount, Operation<Void> original,
            @Share("refused") LocalBooleanRef refused) {
        if (!refused.get()) original.call(stack, amount);
    }
}
