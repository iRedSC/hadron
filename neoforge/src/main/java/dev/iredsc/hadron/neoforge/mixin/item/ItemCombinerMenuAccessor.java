package dev.iredsc.hadron.neoforge.mixin.item;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ResultContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An anvil's or smithing table's viewer, inputs and result. */
@Mixin(ItemCombinerMenu.class)
public interface ItemCombinerMenuAccessor {
    @Accessor("player")
    Player hadron$player();

    @Accessor("inputSlots")
    Container hadron$inputSlots();

    @Accessor("resultSlots")
    ResultContainer hadron$resultSlots();
}
