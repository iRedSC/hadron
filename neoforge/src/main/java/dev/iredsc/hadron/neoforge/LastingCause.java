package dev.iredsc.hadron.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Place;
import java.lang.ref.WeakReference;
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
import org.jspecify.annotations.Nullable;

/**
 * Who applied an effect or set an entity alight, and where: kept on each effect instance (hidden ones included) and on
 * a burning entity, saved with it, and put on the actor stack while the effect or the fire acts ({@link Lasting}).
 *
 * <p>Only what can be saved is kept: what the cause was and who was behind it, never the thing itself. While the
 * cause is still there it acts as itself (the player, the cloud, the machine), for the people it had when it applied
 * this; once it is gone it acts as an {@link Actor.Remembered}, for the same people. Nature and {@link Actor.Unknown}
 * are kept as themselves, so they act as themselves too, not as whoever happens to be on the stack.
 */
public final class LastingCause implements Lasting.Acting {
    /** Who it was, in a form that can be saved. */
    sealed interface Who {}

    /** Nobody: the world on its own, or a mod nothing said anything about. */
    enum Nobody implements Who {
        NATURE,
        UNKNOWN
    }

    /** The server on an operator's orders: the {@code /effect} command. */
    record ServerWho(String name) implements Who {}

    /** An entity, a player included, or a remembered actor ({@code id} empty for a gone machine). */
    record EntityWho(ResourceLocation kind, Optional<UUID> id, Attribution attribution) implements Who {}

    /** A block entity at a position. */
    record MachineWho(ResourceLocation kind, GlobalPos at, Attribution attribution) implements Who {}

    private static final ResourceLocation PLAYER = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);

    /**
     * Saved as {@code applied_at} (a dimension and a position) and who: {@code server} (a name), or {@code kind} with
     * {@code id} or {@code machine} and {@code attribution}, or {@code nobody} ({@code unknown}); none of them is
     * nature. Fields it doesn't know are ignored, so a newer or older layout still loads.
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
            UUIDUtil.CODEC.optionalFieldOf("id").forGetter(c -> c.who instanceof EntityWho e
                    ? e.id() : Optional.empty()),
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
    /**
     * What it last resolved to, checked each time it is asked for and looked up again once that has gone. Held
     * strongly, so asking again allocates nothing; a removed entity is let go the next time the cause acts.
     */
    private @Nullable Actor resolved;
    /** The entity it was applied by, only to find it without a search the first time it acts; weak until then. */
    private @Nullable WeakReference<Entity> appliedBy;
    /** For a machine, the block entity it resolved to: a different one put in its place is not it. */
    private @Nullable BlockEntity machine;
    /** A machine found broken (its spot loaded and holding no block entity of its type): it stays remembered. */
    private boolean machineGone;

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
            who = new MachineWho(kind.get(), machine.get(), people);
        } else if (kind.isPresent()) {
            who = new EntityWho(kind.get(), id, people);
        } else {
            who = nobody.filter("unknown"::equals).isPresent() ? Nobody.UNKNOWN : Nobody.NATURE;
        }
        return new LastingCause(who, where);
    }

    /**
     * The cause of something applied to {@code subject} now, where it stands: {@code actor}, which may be the subject
     * itself (a player's own potion), or {@link Actor.Nature} for null, when nothing at all is behind it.
     */
    public static LastingCause of(@Nullable Actor actor, Entity subject) {
        Actor by = actor != null ? actor : Actor.Nature.INSTANCE;
        LastingCause cause = new LastingCause(who(by), GlobalPos.of(subject.level().dimension(),
                subject.blockPosition()));
        switch (by) {
            case Actor.Player p -> cause.appliedBy = new WeakReference<>(p.player());
            case Actor.FakePlayer f -> cause.appliedBy = new WeakReference<>(f.fake());
            case Actor.EntityActor e -> cause.appliedBy = new WeakReference<>(e.entity());
            case Actor.Machine m -> cause.machine = m.level().isLoaded(m.pos()) ? m.level().getBlockEntity(m.pos())
                    : null;
            default -> { }
        }
        return cause;
    }

    private static Who who(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> entityWho(p.player(), p.attribution());
            case Actor.FakePlayer f -> entityWho(f.fake(), f.attribution());
            case Actor.EntityActor e -> entityWho(e.entity(), e.attribution());
            case Actor.Machine m -> new MachineWho(machineKind(m.level(), m.pos()),
                    GlobalPos.of(m.level().dimension(), m.pos()), m.attribution());
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

    /** A machine's block entity type, or its block's id where no block entity is there to say. */
    private static ResourceLocation machineKind(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
        if (be != null) return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
    }

    /** Whether this cause is {@code entity} itself: its own splash potion, its own poison. */
    boolean names(Entity entity) {
        return who instanceof EntityWho e && e.id().isPresent() && e.id().get().equals(entity.getUUID());
    }

    /**
     * Who acts now: the cause itself while it is still there, for the people it had when it applied this (a bridge's
     * turret firing for its owner keeps that owner), otherwise an {@link Actor.Remembered} for the same people;
     * {@link Actor.Nature} or {@link Actor.Unknown} for nobody. Looked up once and kept while it holds, so asking again
     * costs a check and allocates nothing.
     */
    public Actor actor(MinecraftServer server) {
        Actor kept = resolved;
        if (kept != null && !stale(kept, server)) return kept;
        Actor actor = resolve(server);
        resolved = actor;
        return actor;
    }

    /**
     * Whether what it resolved to no longer holds: an entity removed, a machine's block entity gone (broken, replaced,
     * or its chunk unloaded), a machine remembered while its spot was unloaded and now loaded, a remembered player back
     * online.
     */
    private boolean stale(Actor kept, MinecraftServer server) {
        return switch (kept) {
            case Actor.Player p -> p.player().isRemoved();
            case Actor.FakePlayer f -> f.fake().isRemoved();
            case Actor.EntityActor e -> e.entity().isRemoved();
            case Actor.Machine m -> machine == null || machine.isRemoved();
            case Actor.Remembered r when who instanceof MachineWho m -> !machineGone && loaded(server, m.at()) != null;
            case Actor.Remembered r -> r.kind().equals(PLAYER) && r.id().isPresent()
                    && server.getPlayerList().getPlayer(r.id().get()) != null;
            default -> false;
        };
    }

    private static @Nullable ServerLevel loaded(MinecraftServer server, GlobalPos at) {
        ServerLevel level = server.getLevel(at.dimension());
        return level != null && level.isLoaded(at.pos()) ? level : null;
    }

    private Actor resolve(MinecraftServer server) {
        return switch (who) {
            case Nobody n -> n == Nobody.UNKNOWN ? Actor.Unknown.INSTANCE : Actor.Nature.INSTANCE;
            case ServerWho s -> new Actor.Server(s.name());
            case EntityWho e -> {
                WeakReference<Entity> hint = appliedBy;
                appliedBy = null;
                Entity first = hint == null ? null : hint.get();
                Entity found = first != null && !first.isRemoved() ? first
                        : e.id().map(id -> find(server, e.kind(), id)).orElse(null);
                yield found != null ? EntityActors.as(found, e.attribution())
                        : new Actor.Remembered(e.kind(), e.id(), e.attribution());
            }
            case MachineWho m -> resolveMachine(server, m);
        };
    }

    /**
     * The machine while it is at its spot: the very block entity it resolved to, as long as that was never seen
     * replaced. Only where nothing could tell machines apart, after a restart or once its spot was seen unloaded, does
     * a block entity of the same type there count as it. Anything else at a loaded spot (a furnace where a chest was,
     * a new chest where the old one was broken, nothing) means the machine is gone: remembered for good.
     */
    private Actor resolveMachine(MinecraftServer server, MachineWho m) {
        BlockEntity kept = machine;
        ServerLevel level = loaded(server, m.at());
        machine = null;
        if (level == null) return new Actor.Remembered(m.kind(), Optional.empty(), m.attribution());
        BlockEntity there = level.getBlockEntity(m.at().pos());
        boolean same = there != null && (kept == null || kept == there)
                && BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(there.getType()).equals(m.kind());
        if (!same) {
            machineGone = true;
            return new Actor.Remembered(m.kind(), Optional.empty(), m.attribution());
        }
        machine = there;
        return new Actor.Machine(level, m.at().pos(), m.attribution());
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
    public String toString() {
        return "LastingCause[" + who + " at " + where + "]";
    }
}
