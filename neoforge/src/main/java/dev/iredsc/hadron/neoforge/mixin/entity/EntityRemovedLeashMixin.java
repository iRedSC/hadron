package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity destroyed while still on a lead: a pig struck into a zombified piglin, a mob discarded by a command or a
 * mod. Vanilla never drops the lead for these, so it would vanish unheard. Not for unloading or a portal, where the lead
 * is saved or carried on.
 */
@Mixin(Entity.class)
abstract class EntityRemovedLeashMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void hadron$holder(Entity.RemovalReason reason, CallbackInfo ci,
            @Share("holder") LocalRef<Entity> holder) {
        holder.set(EntityHandling.leashHolderIfDestroyed((Entity) (Object) this, reason));
    }

    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void hadron$report(Entity.RemovalReason reason, CallbackInfo ci,
            @Share("holder") LocalRef<Entity> holder) {
        Entity was = holder.get();
        if (was != null) EntityHandling.leashedEntityRemoved((Entity) (Object) this, was);
    }
}
