package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.ItemHooks;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The item stack chokepoints every holder goes through: using in the air (ItemUseEvent, players and fake players),
 * finishing eating or drinking (ItemConsumeEvent), losing durability (ItemDamageEvent) and breaking (ItemBreakEvent).
 */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void hadron$use(Level level, Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (ItemHooks.use(self, level, player, hand)) cir.setReturnValue(InteractionResultHolder.fail(self));
    }

    /** Returning the stack itself is how vanilla says "nothing consumed"; the user then just stops using it. */
    @Inject(method = "finishUsingItem", at = @At("HEAD"), cancellable = true)
    private void hadron$consume(Level level, LivingEntity entity, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack) (Object) this;
        if (ItemHooks.consume(self, level, entity)) cir.setReturnValue(self);
    }

    /**
     * The durability about to be lost, after Unbreaking. Returning 0 makes vanilla return before touching the item,
     * so a cancel loses nothing and can't break it.
     */
    @ModifyExpressionValue(
            method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;processDurabilityChange(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;I)I"))
    private int hadron$damage(int damage, @Local(argsOnly = true) ServerLevel level,
            @Local(argsOnly = true) LivingEntity entity) {
        return ItemHooks.damage((ItemStack) (Object) this, level, entity, damage);
    }

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void hadron$broke(int damage, ServerLevel level, LivingEntity entity, Consumer<Item> onBreak,
            CallbackInfo ci) {
        ItemHooks.broke((ItemStack) (Object) this, level, entity);
    }
}
