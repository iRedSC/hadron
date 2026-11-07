package dev.iredsc.hadron.neoforge.mixin.world;

import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets ExplosionPrimeEvent change an explosion's size and fire before it goes off. */
@Mixin(Explosion.class)
public interface ExplosionAccessor {
    @Accessor("fire")
    boolean hadron$fire();

    @Mutable
    @Accessor("fire")
    void hadron$setFire(boolean fire);

    @Mutable
    @Accessor("radius")
    void hadron$setRadius(float radius);
}
