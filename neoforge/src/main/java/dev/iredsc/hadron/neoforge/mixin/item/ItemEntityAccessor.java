package dev.iredsc.hadron.neoforge.mixin.item;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Resets an item entity's age, so a cancelled despawn gets a whole new lifespan. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("age")
    void hadron$setAge(int age);
}
