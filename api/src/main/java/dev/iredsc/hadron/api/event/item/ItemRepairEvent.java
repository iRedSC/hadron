package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An item is about to get durability back. Cancel it and the item stays as damaged as it was, and whatever paid for
 * the repair (experience, levels, materials) is not spent.
 *
 * <p>Core fires it for Mending (experience orbs; cancelled, the experience goes to the player instead) and for a
 * player taking an anvil or grindstone result that repairs without changing enchantments. Bridges fire it for their
 * mod's repairs.
 */
@FiredBy(core = PARTIAL, coreSees = "anvil, grindstone, Mending", bridges = "the mod's repairs")
public class ItemRepairEvent extends ItemChangeEvent {
    public ItemRepairEvent(ServerLevel level, InventoryHolder holder, ItemStack before, ItemStack after, Actor actor) {
        super(level, holder, before, after, actor);
    }

    /** Durability restored. */
    public int getAmount() {
        return getBefore().getDamageValue() - getAfter().getDamageValue();
    }
}
