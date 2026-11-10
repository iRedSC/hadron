package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.ItemFrameChangeEvent;
import dev.iredsc.hadron.api.event.entity.ItemFrameChangeEvent.ItemFrameChangeAction;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every way a frame's item changes in play. Each hook sits just before vanilla acts, past its own checks (a fixed
 * frame, a full map, an empty hand), so the event fires only for a change that is about to happen.
 */
@Mixin(ItemFrame.class)
abstract class ItemFrameMixin {
    /** True to let it change. */
    @Unique
    private boolean hadron$changes(ItemFrameChangeAction action, ItemStack item, Actor actor) {
        ItemFrame self = (ItemFrame) (Object) this;
        if (!(self.level() instanceof ServerLevel) || !Hooks.listens(ItemFrameChangeEvent.class)) return true;
        return !Hooks.post(new ItemFrameChangeEvent(self, action, item, actor)).isCancelled();
    }

    @Inject(method = "interact", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/ItemFrame;setItem(Lnet/minecraft/world/item/ItemStack;)V"))
    private void hadron$place(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        // CONSUME, as vanilla answers a use it handled: PASS would offer the other hand a second try.
        if (!hadron$changes(ItemFrameChangeAction.PLACE, player.getItemInHand(hand).copyWithCount(1),
                EntityActors.of(player))) cir.setReturnValue(InteractionResult.CONSUME);
    }

    // Before the click sound, which comes first in vanilla, so a refused turn is silent.
    @Inject(method = "interact", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/ItemFrame;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void hadron$rotate(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        ItemFrame self = (ItemFrame) (Object) this;
        if (!hadron$changes(ItemFrameChangeAction.ROTATE, self.getItem().copyWithCount(1), EntityActors.of(player))) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }

    // A hit on a frame that holds something knocks the item out instead of breaking the frame.
    @Inject(method = "hurt", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/ItemFrame;dropItem(Lnet/minecraft/world/entity/Entity;Z)V"))
    private void hadron$remove(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ItemFrame self = (ItemFrame) (Object) this;
        if (!hadron$changes(ItemFrameChangeAction.REMOVE, self.getItem().copyWithCount(1),
                EntityActors.damage(source, self))) cir.setReturnValue(false);
    }
}
