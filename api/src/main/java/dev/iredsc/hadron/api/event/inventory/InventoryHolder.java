package dev.iredsc.hadron.api.event.inventory;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * What holds an inventory: a block, an entity, an item inside another item, or a frequency in linked storage. Never
 * where a player happens to be standing, so a chest opened through a remote terminal is still the chest. Sealed, so a
 * {@code switch} over it must handle every case:
 *
 * <pre>{@code
 * switch (event.getHolder()) {
 *     case InventoryHolder.Block b -> ...   // b.level(), b.pos()
 *     case InventoryHolder.Entity e -> ...  // a player, a chest minecart, a horse, a villager
 *     case InventoryHolder.Item i -> ...    // a backpack, and i.outer() is what holds the backpack
 *     case InventoryHolder.Linked l -> ...  // an ender chest, an ender tank frequency
 *     case InventoryHolder.Unknown u -> ... // a mod's inventory nothing identified
 * }
 * }</pre>
 */
public sealed interface InventoryHolder {
    /** A block's inventory: a chest, a furnace, a machine. A crafting table or anvil's grid counts as its block's. */
    record Block(ServerLevel level, BlockPos pos) implements InventoryHolder {
        public Block {
            Objects.requireNonNull(level, "level");
            pos = Objects.requireNonNull(pos, "pos").immutable();
        }
    }

    /** An entity's inventory: a player's, a chest minecart's, a horse's, a villager's. */
    record Entity(net.minecraft.world.entity.Entity entity) implements InventoryHolder {
        public Entity {
            Objects.requireNonNull(entity, "entity");
        }
    }

    /** An inventory stored inside an item, such as a backpack; {@code outer} holds that item. */
    record Item(InventoryHolder outer, ItemStack stack) implements InventoryHolder {
        public Item {
            Objects.requireNonNull(outer, "outer; use InventoryHolder.Unknown.INSTANCE when nothing is known");
            Objects.requireNonNull(stack, "stack");
        }
    }

    /**
     * Storage reached through a frequency rather than a place: a player's ender chest is
     * {@code minecraft:ender_chest} with the player's UUID as the frequency; a mod's ender tank uses its own network
     * id.
     */
    record Linked(ResourceLocation network, String frequency) implements InventoryHolder {
        /** The network vanilla's ender chests use; the frequency is the owning player's UUID. */
        public static final ResourceLocation ENDER_CHEST = ResourceLocation.withDefaultNamespace("ender_chest");

        public Linked {
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(frequency, "frequency");
        }
    }

    /** A mod's inventory that nothing identified. Protection should treat it as belonging to nobody it trusts. */
    record Unknown() implements InventoryHolder {
        public static final Unknown INSTANCE = new Unknown();
    }
}
