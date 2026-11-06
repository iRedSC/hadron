package dev.iredsc.hadron.neoforge.mixin.item;

import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A campfire's per-slot cooking progress, reset when a cook is cancelled so it starts over. */
@Mixin(CampfireBlockEntity.class)
public interface CampfireBlockEntityAccessor {
    @Accessor("cookingProgress")
    int[] hadron$cookingProgress();
}
