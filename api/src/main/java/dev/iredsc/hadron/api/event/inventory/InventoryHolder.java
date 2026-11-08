package dev.iredsc.hadron.api.event.inventory;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * What holds an inventory: a block, an entity, an item inside another item, a frequency in linked storage, or one
 * player's own copy of a block's or entity's inventory. Never where a player happens to be standing, so a chest opened
 * through a remote terminal is still the chest. Sealed, so a {@code switch} over it must handle every case:
 *
 * <pre>{@code
 * switch (event.getHolder()) {
 *     case InventoryHolder.Block b -> ...     // b.level(), b.pos()
 *     case InventoryHolder.Entity e -> ...    // a player, a chest minecart, a horse, a villager
 *     case InventoryHolder.Item i -> ...      // a backpack, and i.outer() is what holds the backpack
 *     case InventoryHolder.Linked l -> ...    // an ender chest, an ender tank frequency
 *     case InventoryHolder.PerPlayer p -> ... // a Lootr chest's copy for p.owner(), at p.at()
 *     case InventoryHolder.Unknown u -> ...   // a mod's inventory nothing identified
 * }
 * }</pre>
 */
public sealed interface InventoryHolder {
    /**
     * What the inventory comes down to, for protection: its {@link Block}, {@link Entity}, {@link Linked} storage or
     * {@link Unknown}, never an {@link Item} or {@link PerPlayer}, so a backpack in a chest or a Lootr chest's copy is
     * the chest. Switch on this rather than on the holder. Deny {@link Unknown}, which belongs to nobody protection
     * trusts. {@link Linked} storage has no place, so decide by its frequency: an {@link Linked#ENDER_CHEST}'s is its
     * owner's UUID; any other network's owner only its mod's bridge knows, so deny it unless the bridge says otherwise.
     */
    default InventoryHolder place() {
        return switch (this) {
            case Item i -> i.outer().place();
            case PerPlayer p -> p.at().place();
            case Block b -> b;
            case Entity e -> e;
            case Linked l -> l;
            case Unknown u -> u;
        };
    }

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

    /**
     * One player's own copy of the inventory at a block or entity, where each player who opens it sees
     * different contents: a Lootr chest, a per-player reward crate. {@code at} is where it is, for protection;
     * {@code owner} is whose copy, so two players' copies are never mistaken for one inventory; {@code at} is
     * {@link Unknown} when a bridge knows whose copy but not where. Only a bridge for the per-player mod names one;
     * without it, core reports whatever it can identify, often {@link Unknown} (Lootr hands vanilla a plain container).
     */
    record PerPlayer(InventoryHolder at, UUID owner) implements InventoryHolder {
        public PerPlayer {
            Objects.requireNonNull(at, "at; pass the holder the copy is at, or InventoryHolder.Unknown.INSTANCE");
            if (at instanceof Linked || at instanceof PerPlayer) throw new IllegalArgumentException("A per-player "
                    + "copy is at a Block, an Entity, an Item or Unknown, not " + at + ": Linked storage is already "
                    + "keyed by its frequency, and another copy's place is its at()");
            Objects.requireNonNull(owner, "owner; pass the UUID of the player whose copy it is");
        }
    }

    /** A mod's inventory that nothing identified. Protection should treat it as belonging to nobody it trusts. */
    record Unknown() implements InventoryHolder {
        public static final Unknown INSTANCE = new Unknown();
    }
}
