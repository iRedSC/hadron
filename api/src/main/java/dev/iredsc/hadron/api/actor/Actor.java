package dev.iredsc.hadron.api.actor;

import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Who is behind an action. Sealed, so a {@code switch} over it must handle every case:
 *
 * <pre>{@code
 * switch (event.getActor()) {
 *     case Actor.Player p -> ...
 *     case Actor.FakePlayer f -> ...
 *     case Actor.Machine m -> ...
 *     case Actor.EntityActor e -> ...
 *     case Actor.Server s -> ...
 *     case Actor.Nature n -> ...
 *     case Actor.Unknown u -> ...
 * }
 * }</pre>
 *
 * Protection should let {@link Server} through, and treat {@link Unknown}, and any other actor whose
 * {@link Attribution#actingFor()} is empty, as an outsider: a gap then shows up as a machine refusing to work in a claim, never as a silent grief.
 */
public sealed interface Actor {
    /** The people behind this actor; empty for {@link Nature} and {@link Unknown}. */
    Attribution attribution();

    /** Who protection checks; see {@link Attribution#actingFor()}. */
    default Optional<UUID> actingFor() {
        return attribution().actingFor();
    }

    /** A real player, acting directly. They are responsible for it. */
    record Player(ServerPlayer player) implements Actor {
        public Player {
            Objects.requireNonNull(player, "player");
        }

        @Override
        public Attribution attribution() {
            return Attribution.of(player.getUUID());
        }
    }

    /** A fake player: a mod's machine using the player code path, such as a deployer or a digital miner. */
    record FakePlayer(ServerPlayer fake, Attribution attribution) implements Actor {
        public FakePlayer {
            Objects.requireNonNull(fake, "fake");
            Objects.requireNonNull(attribution, "attribution; use Attribution.none() for nobody");
        }
    }

    /** A block entity acting from its position: a quarry, a turret block, a pump. */
    record Machine(ServerLevel level, BlockPos pos, Attribution attribution) implements Actor {
        public Machine {
            Objects.requireNonNull(level, "level");
            pos = Objects.requireNonNull(pos, "pos").immutable();
            Objects.requireNonNull(attribution, "attribution; use Attribution.none() for nobody");
        }
    }

    /**
     * An identifiable entity: a drone, a vehicle, a projectile, a summon, a wild mob. A mob is always this, even
     * when it spawned naturally, so a logger never loses which creature did it.
     */
    record EntityActor(Entity entity, Attribution attribution) implements Actor {
        public EntityActor {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(attribution, "attribution; use Attribution.none() for nobody");
        }
    }

    /**
     * An actor that caused something and has since gone, kept by what it was rather than by the thing itself: a player
     * who logged off before their poison wore off, a spent area-effect cloud, a removed entity, a broken machine. Core
     * hands it out where it kept a cause across ticks (an effect, an entity's fire) and that cause is no longer
     * there; a bridge carrying an actor into later ticks (a ritual, a turret's lingering cloud) uses it the same way.
     * Protection treats it like any other actor: it acts for whoever {@link #actingFor()} says.
     *
     * @param kind what it was, namespaced: an entity type's id ({@code minecraft:player},
     *     {@code minecraft:area_effect_cloud}), or a block entity type's for a machine
     * @param id which one: the entity's UUID (a player's is their account's), or for a machine the id Hadron gave its
     *     block entity; empty where there was none
     * @param attribution the people who were behind it; {@link Attribution#none()} for nobody
     */
    record Remembered(ResourceLocation kind, Optional<UUID> id, Attribution attribution) implements Actor {
        public Remembered {
            Objects.requireNonNull(kind, "kind; such as the entity type's id, minecraft:player");
            Objects.requireNonNull(id, "id; use Optional.empty() for a machine, which has none");
            Objects.requireNonNull(attribution, "attribution; use Attribution.none() for nobody");
        }
    }

    /**
     * The server itself, acting on an operator's orders: the console, RCON, a command block, a datapack function.
     * Trusted: protection lets it through, and a logger records it as an admin action. {@code name} says which, such
     * as {@code Server} for the console, {@code @} for a command block, or {@code function mypack:tick}.
     */
    record Server(String name) implements Actor {
        public Server {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public Attribution attribution() {
            return Attribution.none();
        }
    }

    /** The world on its own, with no entity behind it: fire spreading, water flowing, crops growing. */
    record Nature() implements Actor {
        public static final Nature INSTANCE = new Nature();

        @Override
        public Attribution attribution() {
            return Attribution.none();
        }
    }

    /** A mod acted and nothing said who. Protection treats it as an outsider. */
    record Unknown() implements Actor {
        public static final Unknown INSTANCE = new Unknown();

        @Override
        public Attribution attribution() {
            return Attribution.none();
        }
    }
}
