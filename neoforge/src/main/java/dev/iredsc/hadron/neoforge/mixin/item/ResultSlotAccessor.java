package dev.iredsc.hadron.neoforge.mixin.item;

import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The crafting grid a result slot crafts from: the craft's inputs. */
@Mixin(ResultSlot.class)
public interface ResultSlotAccessor {
    @Accessor("craftSlots")
    CraftingContainer hadron$craftSlots();
}
