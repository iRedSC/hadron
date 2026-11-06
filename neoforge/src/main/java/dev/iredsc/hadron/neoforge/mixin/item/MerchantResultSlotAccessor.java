package dev.iredsc.hadron.neoforge.mixin.item;

import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Who a trade result slot trades with. */
@Mixin(MerchantResultSlot.class)
public interface MerchantResultSlotAccessor {
    @Accessor("merchant")
    Merchant hadron$merchant();
}
