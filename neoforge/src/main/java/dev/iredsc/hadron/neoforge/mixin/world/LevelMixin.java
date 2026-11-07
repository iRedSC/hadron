package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every explosion through Level.explode is one operation with the explosion's actor on top, so the prime event, the
 * explosion event and every block it changes share it, and a rollback undoes the blast as one.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @WrapMethod(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;"
            + "Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;"
            + "ZLnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;"
            + "Lnet/minecraft/core/Holder;)Lnet/minecraft/world/level/Explosion;")
    private Explosion hadron$operation(Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator,
            double x, double y, double z, float radius, boolean fire, Level.ExplosionInteraction interaction,
            boolean particles, ParticleOptions small, ParticleOptions large, Holder<SoundEvent> sound,
            Operation<Explosion> original) {
        if (!((Object) this instanceof ServerLevel) || Hooks.host() == null) {
            return original.call(source, damageSource, calculator, x, y, z, radius, fire, interaction, particles,
                    small, large, sound);
        }
        // Inside an operation already open (a bridge's spell), the blast joins it; otherwise it is its own.
        Actor actor = WorldActors.of(source);
        try (Actors.Scope scope = dev.iredsc.hadron.api.actor.Operation.current() != null ? Actors.push(actor)
                : dev.iredsc.hadron.api.actor.Operation.begin(actor)) {
            return original.call(source, damageSource, calculator, x, y, z, radius, fire, interaction, particles,
                    small, large, sound);
        }
    }
}
