package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.data.NamespacedKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * What holds an inventory: a block, an entity, an item inside another item, a frequency in linked storage, one
 * player's own copy of a block's or entity's inventory, or a plugin's own container. Never where a player happens to be standing, so a chest opened
 * through a remote terminal is still the chest. Sealed, so a {@code switch} over it must handle every case:
 *
 * <pre>{@code
 * switch (event.getHolder()) {
 *     case InventoryHolder.Block b -> ...     // b.level(), b.pos()
 *     case InventoryHolder.Entity e -> ...    // a player, a chest minecart, a horse, a villager
 *     case InventoryHolder.Item i -> ...      // a backpack, and i.outer() is what holds the backpack
 *     case InventoryHolder.Linked l -> ...    // an ender chest, an ender tank frequency
 *     case InventoryHolder.PerPlayer p -> ... // a Lootr chest's copy for p.owner(), at p.at()
 *     case InventoryHolder.Plugin p -> ...    // a plugin's shop or kit menu, p.id() names which
 *     case InventoryHolder.Unknown u -> ...   // a mod's inventory nothing identified
 * }
 * }</pre>
 *
 * <p>Where each one is, for protection, is {@code Place.of(holder)}: an item's inventory is where its outer holder is,
 * a per-player copy is at its {@code at()}, linked storage and a plugin's own container are {@code Place.Nowhere}, a
 * plugin's view of another inventory is where that inventory is, and one nothing identified is {@code Place.Unknown}.
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

    /**
     * A plugin's own container ({@code PluginContainer}, made with {@code Hadron.createInventory}): a shop, a kit
     * menu, a frequency chest. {@code id} is in the plugin's namespace, so a plugin tells its own menus apart with
     * {@code holder instanceof InventoryHolder.Plugin p && p.id().equals(SHOP)}, and no two plugins' ids collide.
     * {@code at} is the real inventory it shows, when it shows one (a remote view of a chest): its place is then
     * that inventory's place ({@code Place.of}), so protection checks the chest rather than trusting the plugin; with no
     * {@code at} it is {@code Place.Nowhere} of kind {@code Place.Nowhere.PLUGIN_CONTAINER}, which protection lets
     * through.
     */
    record Plugin(NamespacedKey id, Optional<InventoryHolder> at) implements InventoryHolder {
        public Plugin {
            Objects.requireNonNull(id, "id; use new NamespacedKey(plugin, \"shop\")");
            Objects.requireNonNull(at, "at; use Optional.empty() for a container that shows no other inventory");
        }

        /** A plugin's container that shows no other inventory. */
        public Plugin(NamespacedKey id) {
            this(id, Optional.empty());
        }
    }

    /** A mod's inventory that nothing identified. Protection should treat it as belonging to nobody it trusts. */
    record Unknown() implements InventoryHolder {
        public static final Unknown INSTANCE = new Unknown();
    }
}
