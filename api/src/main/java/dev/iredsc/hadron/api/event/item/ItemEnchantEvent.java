package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An item's enchantments are about to change: added at an enchanting table or anvil, removed at a grindstone, or by a
 * mod's ritual, library or affix. Replaces Bukkit's {@code EnchantItemEvent}. Cancel it and the item keeps its
 * enchantments, and no levels or lapis are spent.
 *
 * <p>Core fires it for the enchanting table, and for a player taking an anvil or grindstone result whose enchantments
 * differ from the input. Bridges fire it for their mod's enchanting.
 */
@FiredBy(core = PARTIAL, coreSees = "enchanting table, anvil, grindstone", bridges = "the mod's enchanting")
public class ItemEnchantEvent extends ItemChangeEvent {
    private final int levelCost;

    /**
     * @param levelCost experience levels the change costs; 0 if none
     */
    public ItemEnchantEvent(ServerLevel level, InventoryHolder holder, ItemStack before, ItemStack after, int levelCost,
            Actor actor) {
        super(level, holder, before, after, actor);
        this.levelCost = levelCost;
    }

    /** Enchantments before, including a book's stored ones. */
    public ItemEnchantments getEnchantmentsBefore() {
        return EnchantmentHelper.getEnchantmentsForCrafting(getBefore());
    }

    /** Enchantments after, including a book's stored ones. */
    public ItemEnchantments getEnchantmentsAfter() {
        return EnchantmentHelper.getEnchantmentsForCrafting(getAfter());
    }

    /** Experience levels it costs. */
    public int getLevelCost() {
        return levelCost;
    }
}
