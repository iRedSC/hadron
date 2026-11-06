package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.EntityFishing;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PlayerFishEvent's bite, escape and reel-in steps; the cast is asked in {@code EntitySpawns}. Every cancelled
 * reel-in returns before the bobber is discarded, so it stays out and the rod takes no damage.
 */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
    @Shadow
    private Entity hookedIn;

    /** This reel-in was already reported as a catch, so the end of {@code retrieve} doesn't report it again. */
    @Unique
    private boolean hadron$reported;

    /** The plugin-chosen experience for this reel-in, already dropped; vanilla's orbs are skipped. -1 if none. */
    @Unique
    private int hadron$exp = EntityFishing.VANILLA;

    @Inject(method = "catchingFish", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/FishingHook;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void hadron$bite(BlockPos pos, CallbackInfo ci) {
        if (EntityFishing.cancelBite((FishingHook) (Object) this)) ci.cancel(); // the lure timer starts over
    }

    /** The first {@code DATA_BITING} write in {@code catchingFish} is the nibble running out: the fish got away. */
    @Inject(method = "catchingFish", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/network/syncher/SynchedEntityData;set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V"))
    private void hadron$escaped(BlockPos pos, CallbackInfo ci) {
        EntityFishing.failed((FishingHook) (Object) this);
    }

    @Inject(method = "retrieve", at = @At("HEAD"))
    private void hadron$reelStart(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        hadron$reported = false;
        hadron$exp = EntityFishing.VANILLA;
    }

    @Inject(method = "retrieve", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/FishingHook;pullEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void hadron$caughtEntity(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        hadron$reported = true;
        if (EntityFishing.cancelCatchEntity((FishingHook) (Object) this, hookedIn)) cir.setReturnValue(0);
    }

    /** The fish branch's advancement trigger: after NeoForge's ItemFishedEvent, before any item is handed over. */
    @Inject(method = "retrieve", cancellable = true, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/advancements/critereon/FishingRodHookedTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/projectile/FishingHook;Ljava/util/Collection;)V"))
    private void hadron$caughtFish(ItemStack rod, CallbackInfoReturnable<Integer> cir,
            @Local List<ItemStack> loot) {
        hadron$reported = true;
        int exp = EntityFishing.catchFish((FishingHook) (Object) this, loot);
        if (exp == EntityFishing.CANCELLED) {
            cir.setReturnValue(0);
            return;
        }
        hadron$exp = exp;
    }

    /** Vanilla's experience orb per caught item, skipped when a listener already decided the experience. */
    @WrapOperation(method = "retrieve", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean hadron$catchExp(Level level, Entity orb, Operation<Boolean> original) {
        if (hadron$exp != EntityFishing.VANILLA) return true;
        return original.call(level, orb);
    }

    /** The final discard: reeling in with nothing caught, from the water or the ground. */
    @Inject(method = "retrieve", cancellable = true, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/entity/projectile/FishingHook;discard()V"))
    private void hadron$reelIn(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        if (!hadron$reported && EntityFishing.cancelReel((FishingHook) (Object) this)) cir.setReturnValue(0);
    }
}
