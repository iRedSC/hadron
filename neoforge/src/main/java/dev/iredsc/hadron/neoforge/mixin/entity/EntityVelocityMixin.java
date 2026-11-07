package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityVelocities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** EntityVelocityEvent; rewrites the argument rather than wrapping the call, so this hot path allocates nothing. */
@Mixin(Entity.class)
abstract class EntityVelocityMixin {
    @ModifyVariable(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true)
    private Vec3 hadron$velocity(Vec3 next) {
        return EntityVelocities.velocity((Entity) (Object) this, next);
    }
}
