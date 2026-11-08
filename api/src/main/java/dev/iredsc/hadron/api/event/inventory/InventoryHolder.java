package dev.iredsc.hadron.api.event.inventory;

import java.util.Objects;
import java.util.Optional;
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
     * Where the inventory is, for protection: the block or entity itself, the place a per-player copy belongs to, or
     * whatever holds the item a backpack is in. Check this rather than matching {@link Block} alone, so a Lootr chest
     * or a backpack in a chest is protected too. {@link Unknown} when nothing identified where it is, even through
     * a backpack, which protection denies as belonging to nobody it trusts. Empty only for {@link Linked} storage,
     * which has no place: empty says nothing about who owns it. Decide by its network and frequency: an
     * {@link Linked#ENDER_CHEST}'s frequency is its owner's UUID; any other network's owner only its mod's bridge
     * knows, so deny it unless the bridge says otherwise.
     */
    default Optional<InventoryHolder> place() {
        return switch (this) {
            case Block b -> Optional.of(b);
            case Entity e -> Optional.of(e);
            case Item i -> i.outer().place();
            case PerPlayer p -> Optional.of(p.at());
            case Linked l -> Optional.empty();
            case Unknown u -> Optional.of(u);
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
     * {@code owner} is whose copy, so two players' copies are never mistaken for one inventory. Only a bridge for the
     * per-player mod names one; without it, core reports whatever it can identify, often {@link Unknown} (Lootr hands
     * vanilla a plain container, not the chest), so the chest's place also needs that bridge.
     */
    record PerPlayer(InventoryHolder at, UUID owner) implements InventoryHolder {
        public PerPlayer {
            Objects.requireNonNull(at, "at; pass the Block or Entity holder the copy belongs to");
            switch (at) {
                case Block b -> { }
                case Entity e -> { }
                case Unknown u -> throw new IllegalArgumentException("A per-player copy of a place nothing identified "
                        + "is still unidentified: use InventoryHolder.Unknown.INSTANCE as the holder, which protection "
                        + "denies");
                case Linked l -> throw new IllegalArgumentException("A per-player copy belongs to a Block or an "
                        + "Entity, not " + at + ": storage with no place, such as an ender chest, is Linked itself");
                case Item it -> throw new IllegalArgumentException("A per-player copy belongs to a Block or an "
                        + "Entity; per-player copies of an item's inventory aren't supported, so name it "
                        + "InventoryHolder.Unknown.INSTANCE, which protection denies");
                case PerPlayer p -> throw new IllegalArgumentException("A per-player copy belongs to a Block or an "
                        + "Entity, not another copy: pass its at(), " + p.at());
            }
            Objects.requireNonNull(owner, "owner; pass the UUID of the player whose copy it is");
        }
    }

    /** A mod's inventory that nothing identified. Protection should treat it as belonging to nobody it trusts. */
    record Unknown() implements InventoryHolder {
        public static final Unknown INSTANCE = new Unknown();
    }
}
