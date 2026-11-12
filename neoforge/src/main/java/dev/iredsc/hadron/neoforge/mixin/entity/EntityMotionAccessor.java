package dev.iredsc.hadron.neoforge.mixin.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own motion resolution, to work out where a piston will move an entity without moving it. */
@Mixin(Entity.class)
public interface EntityMotionAccessor {
    /** Collision against blocks and entities, with step-up: what {@code move} applies. Moves nothing. */
    @Invoker("collide")
    Vec3 hadron$collide(Vec3 delta);

    /** How far pistons have moved it this tick, per axis; capped at 0.51 each. */
    @Accessor("pistonDeltas")
    double[] hadron$pistonDeltas();

    @Accessor("pistonDeltasGameTime")
    long hadron$pistonDeltasGameTime();

    @Accessor("stuckSpeedMultiplier")
    Vec3 hadron$stuckSpeedMultiplier();
}
