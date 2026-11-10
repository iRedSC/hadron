package dev.iredsc.hadron.api.event;

import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Where an action happens, as core or a bridge states it: never guessed. Every action lists its places in
 * {@link Placed#getPlaces()}. Sealed, so a {@code switch} must handle every kind:
 *
 * <pre>{@code
 * for (Place place : event.getPlaces()) {
 *     switch (place) {
 *         case Place.Spot s -> ...     // s.level(), s.box(): one block or an area
 *         case Place.Level l -> ...    // a whole level, such as its weather
 *         case Place.Nowhere n -> ...  // no place in any world: an ender chest's frequency, an account
 *         case Place.Unknown u -> ...  // nobody can say where: u.bound() holds every place it could be
 *     }
 * }
 * }</pre>
 *
 * <p>The {@code of} methods give the place of each part of a payload, so a reader can tell which place belongs to
 * which part: {@link #of(InventoryHolder)}, {@link #of(ResourceHolder)}, {@link #of(Entity)}.
 */
public sealed interface Place {
    /**
     * What an {@link Unknown} place is known to lie within: a {@link Spot}, a whole {@link Level}, or
     * {@link Anywhere} when nothing narrows it.
     */
    sealed interface Bound permits Spot, Level, Anywhere {}

    /**
     * Blocks in one level: a single block, or an area. {@code box} is a copy, both ways: changing the box passed in, or
     * the one {@link #box()} returns, changes nothing here.
     */
    record Spot(ServerLevel level, BoundingBox box) implements Place, Bound {
        public Spot {
            Objects.requireNonNull(level, "level");
            box = copy(Objects.requireNonNull(box, "box; use Spot.at(level, pos) for one block"));
        }

        /** One block. */
        public static Spot at(ServerLevel level, BlockPos pos) {
            return new Spot(level, new BoundingBox(Objects.requireNonNull(pos, "pos")));
        }

        /** The block holding a point, such as where an entity stands. */
        public static Spot at(ServerLevel level, Vec3 point) {
            return at(level, BlockPos.containing(Objects.requireNonNull(point, "point")));
        }

        /** A chunk, its full height. */
        public static Spot chunk(ServerLevel level, ChunkPos chunk) {
            return new Spot(level, new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight(),
                    chunk.getMinBlockZ(), chunk.getMaxBlockX(), level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ()));
        }

        /** A copy of the blocks it covers. */
        @Override
        public BoundingBox box() {
            return copy(box);
        }

        /** Whether it is one block. */
        public boolean isBlock() {
            return box.getXSpan() == 1 && box.getYSpan() == 1 && box.getZSpan() == 1;
        }

        private static BoundingBox copy(BoundingBox b) {
            return new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        }
    }

    /** A whole level: its weather, its time, its loading. */
    record Level(ServerLevel level) implements Place, Bound {
        public Level {
            Objects.requireNonNull(level, "level");
        }
    }

    /** No narrower bound than every level there is. */
    record Anywhere() implements Bound {
        public static final Anywhere INSTANCE = new Anywhere();
    }

    /**
     * Known to have no place in any world, and what it is instead: storage reached through a frequency (an ender
     * chest, a mod's linked tank), an account (money, a mana network), a player before they are placed.
     *
     * <p>Whose it is decides it, not where: a protection plugin can't find it in a region. An ender chest's
     * {@link #owner()} is its player; any other linked network's owner only its mod's bridge knows, so deny it unless
     * that bridge says otherwise. A {@link #PLUGIN_CONTAINER} is a plugin's own: the plugin that made it decides who
     * may use it, so let it through. A protection helper will make this decision once, for plugins that want it.
     *
     * @param kind what it is, namespaced: {@code minecraft:ender_chest}, {@link #ACCOUNT}, {@link #PLAYER}, a mod's
     *     network id
     * @param id which one of its kind: a frequency, an account's id, a player's UUID
     * @param owner whose it is, where that is known for certain; empty otherwise
     */
    record Nowhere(ResourceLocation kind, String id, Optional<UUID> owner) implements Place {
        /** An account, such as {@link ResourceHolder.Account}: money, skill points, a mana network. */
        public static final ResourceLocation ACCOUNT = ResourceLocation.fromNamespaceAndPath("hadron", "account");

        /**
         * A plugin's own container ({@code InventoryHolder.Plugin} with no {@code at}): a shop, a kit menu. Its
         * {@link #id()} is the container's id, {@code plugin:name}.
         */
        public static final ResourceLocation PLUGIN_CONTAINER =
                ResourceLocation.fromNamespaceAndPath("hadron", "plugin_container");

        /** A player themselves, before they have a place: logging in. */
        public static final ResourceLocation PLAYER = ResourceLocation.fromNamespaceAndPath("hadron", "player");

        public Nowhere {
            Objects.requireNonNull(kind, "kind; such as Place.Nowhere.ACCOUNT, or the network's own id");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner; use Optional.empty() when nobody is known for certain");
        }

        /** A player, before they have a place, owned by themselves. */
        public static Nowhere player(UUID player) {
            Objects.requireNonNull(player, "player");
            return new Nowhere(PLAYER, player.toString(), Optional.of(player));
        }

        /**
         * An account. Its owner is empty: an account's id says which account, not who owns it, though a player's own
         * ({@code ResourceHolder.Account.player(uuid)}) uses their UUID as its id.
         */
        public static Nowhere account(ResourceHolder.Account account) {
            return new Nowhere(ACCOUNT, Objects.requireNonNull(account, "account").id(), Optional.empty());
        }

        /** A plugin's own container, which shows no other inventory. Nobody owns it for certain: its plugin does. */
        public static Nowhere pluginContainer(InventoryHolder.Plugin container) {
            Objects.requireNonNull(container, "container");
            return new Nowhere(PLUGIN_CONTAINER, container.id().toString(), Optional.empty());
        }

        /** Linked storage: an ender chest's owner is the player whose UUID is its frequency. */
        public static Nowhere linked(InventoryHolder.Linked linked) {
            Objects.requireNonNull(linked, "linked");
            Optional<UUID> owner = Optional.empty();
            if (linked.network().equals(InventoryHolder.Linked.ENDER_CHEST)) {
                try {
                    owner = Optional.of(UUID.fromString(linked.frequency()));
                } catch (IllegalArgumentException notAUuid) {
                    // A frequency that isn't a UUID names nobody for certain.
                }
            }
            return new Nowhere(linked.network(), linked.frequency(), owner);
        }
    }

    /**
     * Core or a bridge can't say exactly where: {@link #reason()} says why, and {@link #bound()} contains every place
     * it could be. Protection should deny it wherever its bound reaches somewhere protected, as it denies an outsider:
     * then a missing bridge shows as a machine that won't work, never as a hole. Not the same as
     * {@code Actor.Unknown}, which is about who, not where.
     */
    record Unknown(Bound bound, Reason reason) implements Place {
        /** Why a place is unknown. Closed: a new reason is a change to this list. */
        public enum Reason {
            /** An inventory nothing identified: a mod's storage with no bridge naming its holder. */
            UNIDENTIFIED_INVENTORY,
            /** A mod's menu that no bridge describes, so which inventories its slots reach is unknown. */
            UNDESCRIBED_MENU,
            /** An operation that has no place core can state, such as an action of a kind it doesn't know. */
            UNPLACEABLE_OPERATION,
            /** Where something came from that nobody saw, such as where a bridge's transfer set out that it didn't record. */
            UNSEEN_ORIGIN,
            /** A command: its source (the console sits at spawn) isn't where it acts; what it does places itself. */
            COMMAND,
            /** Nobody is behind it, and nothing else in the event says where it happens. */
            NOBODY_BEHIND_IT
        }

        public Unknown {
            Objects.requireNonNull(bound, "bound; use Place.Anywhere.INSTANCE when nothing narrows it");
            Objects.requireNonNull(reason, "reason");
        }

        /** Unknown, and nothing narrows it. */
        public static Unknown anywhere(Reason reason) {
            return new Unknown(Anywhere.INSTANCE, reason);
        }
    }

    /**
     * Where an inventory is: a block's {@link Spot}, and both halves' for a connected double chest, which share one
     * inventory; where an entity stands for an entity's; where its holder is for an item's ({@link
     * InventoryHolder.Item#outer()}), a per-player copy's ({@link InventoryHolder.PerPlayer#at()}) or a plugin's view
     * of another inventory ({@link InventoryHolder.Plugin#at()}); {@link Nowhere} for linked storage and a plugin's
     * own container; and {@link Unknown} for one nothing identified.
     */
    static Places of(InventoryHolder holder) {
        return switch (Objects.requireNonNull(holder, "holder")) {
            case InventoryHolder.Block b -> holding(b.level(), b.pos());
            case InventoryHolder.Entity e -> Places.of(of(e.entity()));
            case InventoryHolder.Item i -> of(i.outer());
            case InventoryHolder.PerPlayer p -> of(p.at());
            case InventoryHolder.Linked l -> Places.of(Nowhere.linked(l));
            case InventoryHolder.Plugin p -> p.at().map(Place::of).orElseGet(() -> Places.of(Nowhere.pluginContainer(p)));
            case InventoryHolder.Unknown u -> Places.of(Unknown.anywhere(Unknown.Reason.UNIDENTIFIED_INVENTORY));
        };
    }

    /**
     * Where a resource is held: a block's {@link Spot} (both halves' for a connected double chest), a chunk's, where an
     * entity stands, where the item holding it is ({@link ResourceHolder.Item#heldIn()}), or {@link Nowhere} for an
     * account.
     */
    static Places of(ResourceHolder holder) {
        return switch (Objects.requireNonNull(holder, "holder")) {
            case ResourceHolder.Block b -> holding(b.level(), b.pos());
            case ResourceHolder.Entity e -> Places.of(of(e.entity()));
            case ResourceHolder.Item i -> of(i.heldIn());
            case ResourceHolder.Chunk c -> Places.of(Spot.chunk(c.level(), c.pos()));
            case ResourceHolder.Account a -> Places.of(Nowhere.account(a));
        };
    }

    /**
     * The block holding an inventory: the block, then, for a connected double chest, the other half, when the chest's
     * block state says the half at {@code pos} is joined to the one beside it and that one says the same back.
     */
    private static Places holding(ServerLevel level, BlockPos pos) {
        Places one = Places.of(Spot.at(level, pos));
        if (!level.isLoaded(pos)) return one;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return one;
        }
        Direction toOther = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(toOther);
        if (!level.isLoaded(other)) return one;
        BlockState otherState = level.getBlockState(other);
        boolean joined = otherState.getBlock() == state.getBlock()
                && otherState.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()
                && ChestBlock.getConnectedDirection(otherState) == toOther.getOpposite();
        return joined ? one.and(Spot.at(level, other)) : one;
    }

    /** The block an entity stands in now. */
    static Place of(Entity entity) {
        if (!(Objects.requireNonNull(entity, "entity").level() instanceof ServerLevel level)) {
            // Only a client's entity has no server level, and no server event names one.
            return Unknown.anywhere(Unknown.Reason.UNPLACEABLE_OPERATION);
        }
        return Spot.at(level, entity.position());
    }
}
