package dev.iredsc.hadron.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Place;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jspecify.annotations.Nullable;

/**
 * Who applied an effect or set an entity alight, and where. Kept per entity: one for each effect in each effect's
 * chain ({@link DataEffectCauses}), and one for its fire ({@link Lasting#fireCause}); saved with it.
 *
 * <p>A value: it holds only what identifies the cause (what it was, its UUID or a machine's id, and the people behind
 * it), never the thing itself, so nothing it names is kept in memory and nothing in it can go stale. It is worked out
 * into an {@link Actor} only when something reads it ({@link Actors.Port.Lazy}): the cause itself while it is there,
 * for the people it had when it applied this (a bridge's turret firing for its owner keeps that owner), otherwise an
 * {@link Actor.Remembered} for the same people. Nature and {@link Actor.Unknown} are causes too, and act as themselves.
 */
public final class LastingCause implements Lasting.Acting, Actors.Port.Lazy {
    /** Who it was, in a form that can be saved. */
    sealed interface Who {}

    /** Nobody: the world on its own, or a mod nothing said anything about. */
    enum Nobody implements Who {
        NATURE,
        UNKNOWN
    }

    /** The server on an operator's orders: the {@code /effect} command. */
    record ServerWho(String name) implements Who {}

    /** An entity, a player included, or a remembered one; {@code id} empty only for a remembered machine without one. */
    record EntityWho(ResourceLocation kind, Optional<UUID> id, Attribution attribution) implements Who {}

    /**
     * A block entity at a position, by the id Hadron gave it ({@link AttributionMemory#machineId}), so another put in
     * its place is never taken for it; empty where its spot held no block entity to give one to.
     */
    record MachineWho(ResourceLocation kind, GlobalPos at, Optional<UUID> id, Attribution attribution) implements Who {}

    private static final ResourceLocation PLAYER = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);

    /**
     * Saved as {@code applied_at} (a dimension and a position) and who: {@code server} (a name), or {@code kind} with
     * {@code id}, plus {@code machine} for a machine, and {@code attribution}; or {@code nobody} ({@code unknown});
     * none of them is nature. Fields it doesn't know are ignored, so a newer or older layout still loads.
     */
    static final Codec<LastingCause> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.fieldOf("applied_at").forGetter(c -> c.where),
            Codec.STRING.optionalFieldOf("server").forGetter(c -> c.who instanceof ServerWho s
                    ? Optional.of(s.name()) : Optional.empty()),
            ResourceLocation.CODEC.optionalFieldOf("kind").forGetter(c -> switch (c.who) {
                case EntityWho e -> Optional.of(e.kind());
                case MachineWho m -> Optional.of(m.kind());
                default -> Optional.empty();
            }),
            UUIDUtil.CODEC.optionalFieldOf("id").forGetter(c -> switch (c.who) {
                case EntityWho e -> e.id();
                case MachineWho m -> m.id();
                default -> Optional.empty();
            }),
            GlobalPos.CODEC.optionalFieldOf("machine").forGetter(c -> c.who instanceof MachineWho m
                    ? Optional.of(m.at()) : Optional.empty()),
            AttributionMemory.CODEC.optionalFieldOf("attribution").forGetter(c -> switch (c.who) {
                case EntityWho e when !e.attribution().isEmpty() -> Optional.of(e.attribution());
                case MachineWho m when !m.attribution().isEmpty() -> Optional.of(m.attribution());
                default -> Optional.empty();
            }),
            Codec.STRING.optionalFieldOf("nobody").forGetter(c -> c.who == Nobody.UNKNOWN
                    ? Optional.of("unknown") : Optional.empty()))
            .apply(i, LastingCause::decode));

    private final Who who;
    private final GlobalPos where;

    private LastingCause(Who who, GlobalPos where) {
        this.who = Objects.requireNonNull(who, "who");
        this.where = Objects.requireNonNull(where, "where");
    }

    private static LastingCause decode(GlobalPos where, Optional<String> server, Optional<ResourceLocation> kind,
            Optional<UUID> id, Optional<GlobalPos> machine, Optional<Attribution> attribution,
            Optional<String> nobody) {
        Attribution people = attribution.orElse(Attribution.none());
        Who who;
        if (server.isPresent()) {
            who = new ServerWho(server.get());
        } else if (kind.isPresent() && machine.isPresent()) {
            who = new MachineWho(kind.get(), machine.get(), id, people);
        } else if (kind.isPresent()) {
            who = new EntityWho(kind.get(), id, people);
        } else {
            who = nobody.filter("unknown"::equals).isPresent() ? Nobody.UNKNOWN : Nobody.NATURE;
        }
        return new LastingCause(who, where);
    }

    /**
     * The cause of something applied to {@code subject} now, where it stands: {@code actor}, which may be the subject
     * itself (a player's own potion), or {@link Actor.Nature} for null, when nothing at all is behind it. A machine's
     * block entity is given its id here if it has none yet.
     */
    public static LastingCause of(@Nullable Actor actor, Entity subject) {
        return new LastingCause(who(actor != null ? actor : Actor.Nature.INSTANCE),
                GlobalPos.of(subject.level().dimension(), subject.blockPosition()));
    }

    private static Who who(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> entityWho(p.player(), p.attribution());
            case Actor.FakePlayer f -> entityWho(f.fake(), f.attribution());
            case Actor.EntityActor e -> entityWho(e.entity(), e.attribution());
            case Actor.Machine m -> machineWho(m);
            case Actor.Remembered r -> new EntityWho(r.kind(), r.id(), r.attribution());
            case Actor.Server s -> new ServerWho(s.name());
            case Actor.Nature n -> Nobody.NATURE;
            case Actor.Unknown u -> Nobody.UNKNOWN;
        };
    }

    private static EntityWho entityWho(Entity entity, Attribution attribution) {
        return new EntityWho(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), Optional.of(entity.getUUID()),
                attribution);
    }

    private static MachineWho machineWho(Actor.Machine m) {
        ServerLevel level = m.level();
        BlockPos pos = m.pos();
        GlobalPos at = GlobalPos.of(level.dimension(), pos);
        BlockEntity be = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        if (be == null) {
            return new MachineWho(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()), at,
                    Optional.empty(), m.attribution());
        }
        return new MachineWho(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()), at,
                Optional.of(AttributionMemory.machineId(be)), m.attribution());
    }

    /** Whether this cause is {@code entity} itself: its own splash potion, its own poison. */
    boolean names(Entity entity) {
        return who instanceof EntityWho e && e.id().isPresent() && e.id().get().equals(entity.getUUID());
    }

    /** Worked out now, on the running server; {@link Actor.Unknown} with none. */
    @Override
    public Actor resolve() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? Actor.Unknown.INSTANCE : actor(server);
    }

    /**
     * Who acts now: the cause itself while it is there, for the people it had when it applied this, otherwise an
     * {@link Actor.Remembered} for the same people. A player is there while online; another entity while loaded,
     * wherever it is now (after a reload too: it is found by its UUID); a machine while the very block entity it was
     * (by its id) is at its spot, loaded.
     */
    public Actor actor(MinecraftServer server) {
        return switch (who) {
            case Nobody n -> n == Nobody.UNKNOWN ? Actor.Unknown.INSTANCE : Actor.Nature.INSTANCE;
            case ServerWho s -> new Actor.Server(s.name());
            case EntityWho e -> {
                Entity found = e.id().isPresent() ? find(server, e.kind(), e.id().get()) : null;
                yield found != null ? EntityActors.as(found, e.attribution())
                        : new Actor.Remembered(e.kind(), e.id(), e.attribution());
            }
            case MachineWho m -> machine(server, m);
        };
    }

    private static Actor machine(MinecraftServer server, MachineWho m) {
        ServerLevel level = server.getLevel(m.at().dimension());
        BlockPos pos = m.at().pos();
        BlockEntity be = level != null && level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        boolean same = level != null && be != null && m.id().isPresent()
                && BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).equals(m.kind())
                && m.id().get().equals(AttributionMemory.machineIdIfAny(be));
        return same ? new Actor.Machine(level, pos, m.attribution())
                : new Actor.Remembered(m.kind(), m.id(), m.attribution());
    }

    private static @Nullable Entity find(MinecraftServer server, ResourceLocation kind, UUID id) {
        if (kind.equals(PLAYER)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player != null && !player.isRemoved() ? player : null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null && !found.isRemoved()) return found;
        }
        return null;
    }

    /** Where it was applied: the block its subject stood in, or unknown where that level is no longer loaded. */
    public Place appliedAt(MinecraftServer server) {
        ServerLevel level = server.getLevel(where.dimension());
        return level == null ? Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN)
                : Place.Spot.at(level, where.pos());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof LastingCause c && who.equals(c.who) && where.equals(c.where);
    }

    @Override
    public int hashCode() {
        return Objects.hash(who, where);
    }

    @Override
    public String toString() {
        return "LastingCause[" + who + " at " + where + "]";
    }
}
