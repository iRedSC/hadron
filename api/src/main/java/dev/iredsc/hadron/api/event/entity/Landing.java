package dev.iredsc.hadron.api.event.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

/**
 * Where an entity placed at a spot really ends up, as the teleport and dismount events state it. Minecraft's one
 * exception to "where it is put": a shulker that isn't riding always sits at the bottom centre of a block
 * ({@code Shulker.setPos} rounds it there).
 */
public final class Landing {
    private Landing() {}

    /** Where {@code entity} ends up when placed at {@code at}, not riding anything. */
    public static Vec3 of(Entity entity, Vec3 at) {
        if (!(entity instanceof Shulker)) return at;
        return new Vec3(Mth.floor(at.x) + 0.5, Mth.floor(at.y + 0.5), Mth.floor(at.z) + 0.5);
    }
}
