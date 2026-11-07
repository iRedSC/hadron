package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.neoforge.EntityActors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A projectile remembers who is behind its shooter, saved with it, so a late hit is still theirs. */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
    @Inject(method = "setOwner", at = @At("TAIL"))
    private void hadron$owner(Entity owner, CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (owner == null || owner == self || !(self.level() instanceof ServerLevel)) return;
        Attribution attribution = EntityActors.attributionOf(owner);
        if (!attribution.isEmpty()) Actors.remember(self, attribution);
    }
}
