package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A mod that runs an Explosion itself, without Level.explode, still gets its block changes grouped under one
 * operation with the explosion's actor.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private Entity source;

    @WrapMethod(method = "finalizeExplosion(Z)V")
    private void hadron$operation(boolean particles, Operation<Void> original) {
        if (!(level instanceof ServerLevel) || Hooks.host() == null || dev.iredsc.hadron.api.actor.Operation.current() != null) {
            original.call(particles);
            return;
        }
        try (Actors.Scope scope = dev.iredsc.hadron.api.actor.Operation.begin(WorldActors.of(source))) {
            original.call(particles);
        }
    }
}
