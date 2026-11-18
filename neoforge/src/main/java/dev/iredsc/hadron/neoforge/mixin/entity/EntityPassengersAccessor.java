package dev.iredsc.hadron.neoforge.mixin.entity;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * An entity's own list of riders, to ask a vehicle where it would seat one more without mounting it: a seat depends
 * on how many ride and in what order.
 */
@Mixin(Entity.class)
public interface EntityPassengersAccessor {
    @Accessor("passengers")
    ImmutableList<Entity> hadron$passengers();

    /** Replaces the list and nothing else: no game event, no packet, no rider told. Put the old one back. */
    @Accessor("passengers")
    void hadron$setPassengers(ImmutableList<Entity> passengers);
}
