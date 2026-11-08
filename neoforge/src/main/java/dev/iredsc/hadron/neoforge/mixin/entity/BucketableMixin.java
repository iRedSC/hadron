package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityRemovals;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scooping a fish into a water bucket is a capture. Hooked in the shared method every bucketable mob calls, so a
 * mod's fish that uses it is covered too; asked before the sound, the bucket or the removal.
 */
@Mixin(Bucketable.class)
interface BucketableMixin {
    @Inject(method = "bucketMobPickup", at = @At("HEAD"), cancellable = true)
    private static void hadron$capture(Player player, InteractionHand hand, LivingEntity entity,
            CallbackInfoReturnable<Optional<InteractionResult>> cir) {
        if (EntityRemovals.refuseBucket(player, hand, (LivingEntity & Bucketable) entity)) {
            cir.setReturnValue(Optional.of(InteractionResult.FAIL));
        }
    }
}
