package dev.iredsc.hadron.regions;

import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * The kind of entity a node acts on, the last segment of most {@code entity.*} nodes. Read only from facts Minecraft
 * states: whether it is a player, and otherwise its type's {@link net.minecraft.world.entity.MobCategory}, which a
 * mod's entity type declares for itself. So a mod's mob or furniture gets the right kind without the plugin knowing
 * the mod.
 */
enum Kind {
    PLAYER,
    MONSTER,
    ANIMAL,
    MISC;

    /** As written in a node: {@code entity.hurt.animal}. */
    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Kind of(Entity entity) {
        if (entity instanceof Player) return PLAYER;
        // No default: a category Minecraft adds fails to compile here until someone decides its kind.
        return switch (entity.getType().getCategory()) {
            case MONSTER -> MONSTER;
            case CREATURE, AMBIENT, AXOLOTLS, WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE -> ANIMAL;
            case MISC -> MISC;
        };
    }
}
