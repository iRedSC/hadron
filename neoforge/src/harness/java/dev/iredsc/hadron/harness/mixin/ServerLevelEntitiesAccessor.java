package dev.iredsc.hadron.harness.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A world's whole entity store, listed or not: the acceptance kit asks it whether a mob that changed worlds still
 * exists while the world it went to doesn't list it yet.
 */
@Mixin(ServerLevel.class)
public interface ServerLevelEntitiesAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> hadron$entityManager();
}
