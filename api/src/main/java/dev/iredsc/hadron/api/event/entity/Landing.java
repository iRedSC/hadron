package dev.iredsc.hadron.api.event.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

/**
 * Where an entity placed at a spot really ends up, for the events that state a landing. Minecraft's own exception: a
 * shulker that isn't riding always sits at the bottom centre of a block ({@code Shulker.setPos} rounds it there).
 */
final class Landing {
    private Landing() {}

    /** Where {@code entity} ends up when placed at {@code at}, {@code riding} or not once it is there. */
    static Vec3 of(Entity entity, Vec3 at, boolean riding) {
        if (!(entity instanceof Shulker) || riding) return at;
        return new Vec3(Mth.floor(at.x) + 0.5, Mth.floor(at.y + 0.5), Mth.floor(at.z) + 0.5);
    }
}
