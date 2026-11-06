package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent.RemoveCause;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every way an item frame or painting breaks: hit, losing its wall, or being pushed. */
@Mixin(BlockAttachedEntity.class)
abstract class BlockAttachedEntityMixin {
    /** True to let it break. */
    @Unique
    private boolean hadron$breaks(RemoveCause cause, Actor actor) {
        BlockAttachedEntity self = (BlockAttachedEntity) (Object) this;
        if (!(self instanceof HangingEntity hanging) || !(self.level() instanceof ServerLevel) || self.isRemoved()
                || !Hooks.listens(HangingBreakEvent.class)) return true;
        return !Hooks.post(new HangingBreakEvent(hanging, cause, actor)).isCancelled();
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void hadron$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        BlockAttachedEntity self = (BlockAttachedEntity) (Object) this;
        if (self.isInvulnerableTo(source)) return;
        RemoveCause cause = source.is(DamageTypeTags.IS_EXPLOSION) ? RemoveCause.EXPLOSION
                : source.getEntity() != null ? RemoveCause.ENTITY : RemoveCause.DEFAULT;
        if (!hadron$breaks(cause, EntityActors.damage(source, self))) cir.setReturnValue(false);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;survives()Z"))
    private boolean hadron$survives(BlockAttachedEntity self, Operation<Boolean> original) {
        if (original.call(self)) return true;
        return !hadron$breaks(RemoveCause.PHYSICS, EntityActors.fromStack(self, Actor.Nature.INSTANCE));
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void hadron$move(MoverType type, Vec3 by, CallbackInfo ci) {
        if (by.lengthSqr() > 0.0 && !hadron$breaks(RemoveCause.MOVED,
                EntityActors.fromStack((BlockAttachedEntity) (Object) this, Actor.Nature.INSTANCE))) {
            ci.cancel();
        }
    }

    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void hadron$push(double x, double y, double z, CallbackInfo ci) {
        if (x * x + y * y + z * z > 0.0 && !hadron$breaks(RemoveCause.MOVED,
                EntityActors.fromStack((BlockAttachedEntity) (Object) this, Actor.Nature.INSTANCE))) {
            ci.cancel();
        }
    }
}
