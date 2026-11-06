package dev.iredsc.hadron.api.event.resource;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/**
 * What holds an amount of a {@link Resource}. Sealed, so a {@code switch} must handle every kind:
 *
 * <pre>{@code
 * switch (event.getHolder()) {
 *     case ResourceHolder.Block b -> ...    // a machine, tank or battery
 *     case ResourceHolder.Entity e -> ...   // a player's mana, a vehicle's fuel
 *     case ResourceHolder.Item i -> ...     // a charged tool, a jetpack
 *     case ResourceHolder.Chunk c -> ...    // aura, radiation, a virtual ore vein
 *     case ResourceHolder.Account a -> ...  // money, skill points
 * }
 * }</pre>
 */
public sealed interface ResourceHolder {
    /** A block, usually through its block entity. */
    record Block(ServerLevel level, BlockPos pos) implements ResourceHolder {
        public Block {
            Objects.requireNonNull(level, "level");
            pos = Objects.requireNonNull(pos, "pos").immutable();
        }
    }

    /** An entity, such as a player's mana pool. */
    record Entity(net.minecraft.world.entity.Entity entity) implements ResourceHolder {
        public Entity {
            Objects.requireNonNull(entity, "entity");
        }
    }

    /** An item stack, such as a charged tool. Changes to the stack itself are item events; this is its store. */
    record Item(ItemStack stack) implements ResourceHolder {
        public Item {
            Objects.requireNonNull(stack, "stack");
        }
    }

    /** A chunk, for resources spread over an area: aura, radiation, ore left in a virtual vein. */
    record Chunk(ServerLevel level, ChunkPos pos) implements ResourceHolder {
        public Chunk {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(pos, "pos");
        }
    }

    /** An account, which holds money and other amounts tied to nobody's body: a player's balance, a bank, a shop. */
    record Account(String id) implements ResourceHolder {
        public Account {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("An account needs an id; use Account.player(uuid) for a player's");
            }
        }

        /** A player's own account, keyed by their UUID so it works while they're offline. */
        public static Account player(UUID player) {
            return new Account(Objects.requireNonNull(player, "player").toString());
        }
    }
}
