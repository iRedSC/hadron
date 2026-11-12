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
 * cause is still there it acts as itself (the player, the cloud, the machine); once it is gone it acts as an
 * {@link Actor.Remembered}, for the same people.
 */
public final class LastingCause implements Lasting.Acting {
    /** Who it was, in a form that can be saved. Nature and {@code Actor.Unknown} are nobody, kept as no cause. */
    sealed interface Who {}

    /** The server on an operator's orders: the {@code /effect} command. */
    record ServerWho(String name) implements Who {}

    /** An entity, a player included, or a remembered actor ({@code id} empty for a gone machine). */
    record EntityWho(ResourceLocation kind, Optional<UUID> id, Attribution attribution) implements Who {}

    /** A block entity at a position. */
    record MachineWho(ResourceLocation kind, GlobalPos at, Attribution attribution) implements Who {}

    private static final ResourceLocation PLAYER = BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER);

    /**
     * Saved as {@code applied_at} (a dimension and a position) and, when someone is known, {@code server} (a name), or
     * {@code kind} with {@code id} or {@code machine} and {@code attribution}. Fields it doesn't know are ignored, and
     * missing ones mean nobody, so a newer or older layout still loads.
     */
    static final Codec<LastingCause> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.fieldOf("applied_at").forGetter(c -> c.where),
            Codec.STRING.optionalFieldOf("server").forGetter(c -> c.who instanceof ServerWho s
                    ? Optional.of(s.name()) : Optional.empty()),
            ResourceLocation.CODEC.optionalFieldOf("kind").forGetter(c -> switch (c.who) {
                case EntityWho e -> Optional.of(e.kind());
                case MachineWho m -> Optional.of(m.kind());
                case null, default -> Optional.empty();
            }),
            UUIDUtil.CODEC.optionalFieldOf("id").forGetter(c -> c.who instanceof EntityWho e
                    ? e.id() : Optional.empty()),
            GlobalPos.CODEC.optionalFieldOf("machine").forGetter(c -> c.who instanceof MachineWho m
                    ? Optional.of(m.at()) : Optional.empty()),
            AttributionMemory.CODEC.optionalFieldOf("attribution").forGetter(c -> switch (c.who) {
                case EntityWho e when !e.attribution().isEmpty() -> Optional.of(e.attribution());
                case MachineWho m when !m.attribution().isEmpty() -> Optional.of(m.attribution());
                case null, default -> Optional.empty();
            }))
            .apply(i, LastingCause::decode));

    private final @Nullable Who who;
    private final GlobalPos where;
    /** The live entity it last resolved to; weak, so a cause never keeps a removed entity in memory. */
    private @Nullable WeakReference<Entity> entity;
    /** What it last resolved to when that isn't an entity: the server, a machine, or a remembered actor. */
    private @Nullable Actor fixed;

    private LastingCause(@Nullable Who who, GlobalPos where) {
        this.who = who;
        this.where = Objects.requireNonNull(where, "where");
    }

    private static LastingCause decode(GlobalPos where, Optional<String> server, Optional<ResourceLocation> kind,
            Optional<UUID> id, Optional<GlobalPos> machine, Optional<Attribution> attribution) {
        Attribution people = attribution.orElse(Attribution.none());
        Who who = null;
        if (server.isPresent()) {
            who = new ServerWho(server.get());
        } else if (kind.isPresent() && machine.isPresent()) {
            who = new MachineWho(kind.get(), machine.get(), people);
        } else if (kind.isPresent()) {
            who = new EntityWho(kind.get(), id, people);
        }
        return new LastingCause(who, where);
    }

    /**
     * The cause of something applied to {@code subject} now, where it stands: {@code actor}, which may be the subject
     * itself (a player's own potion), or nobody for null, {@link Actor.Nature} and {@link Actor.Unknown}.
     */
    public static LastingCause of(@Nullable Actor actor, Entity subject) {
        LastingCause cause = new LastingCause(actor == null ? null : who(actor),
                GlobalPos.of(subject.level().dimension(), subject.blockPosition()));
        if (cause.who == null || actor == null) return cause;
        switch (actor) {
            case Actor.Player p -> cause.entity = new WeakReference<>(p.player());
            case Actor.FakePlayer f -> cause.entity = new WeakReference<>(f.fake());
            case Actor.EntityActor e -> cause.entity = new WeakReference<>(e.entity());
            default -> cause.fixed = actor;
        }
        return cause;
    }

    private static @Nullable Who who(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> entityWho(p.player(), p.attribution());
            case Actor.FakePlayer f -> entityWho(f.fake(), f.attribution());
            case Actor.EntityActor e -> entityWho(e.entity(), e.attribution());
            case Actor.Machine m -> new MachineWho(machineKind(m.level(), m.pos()),
                    GlobalPos.of(m.level().dimension(), m.pos()), m.attribution());
            case Actor.Remembered r -> new EntityWho(r.kind(), r.id(), r.attribution());
            case Actor.Server s -> new ServerWho(s.name());
            case Actor.Nature n -> null;
            case Actor.Unknown u -> null;
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

    /** Whether anyone is known: nobody for nature, an unknown mod, or a fire block or lava, which keep no placer. */
    public boolean hasActor() {
        return who != null;
    }

    /** Whether this cause is {@code entity} itself: its own splash potion, its own poison. */
    boolean names(Entity entity) {
        return who instanceof EntityWho e && e.id().isPresent() && e.id().get().equals(entity.getUUID());
    }

    /**
     * Who acts now: the cause itself while it is still there, for the people it had when it applied this (a bridge's
     * turret firing for its owner keeps that owner), otherwise an {@link Actor.Remembered} for the same people; null
     * for nobody. Remembers what it found, so a cause that is still there costs one check a call, and allocates
     * nothing unless a bridge gave a live entity people other than its own.
     */
    public @Nullable Actor actor(MinecraftServer server) {
        if (who == null) return null;
        WeakReference<Entity> ref = entity;
        Entity live = ref == null ? null : ref.get();
        if (live != null && !live.isRemoved() && who instanceof EntityWho e) {
            return EntityActors.as(live, e.attribution());
        }
        Actor kept = fixed;
        if (live == null && kept != null && !stale(kept, server)) return kept;
        return resolve(server);
    }

    /** Whether a kept actor should be looked up again: a machine that has gone, a remembered player back online. */
    private boolean stale(Actor kept, MinecraftServer server) {
        return switch (kept) {
            case Actor.Machine m -> !m.level().isLoaded(m.pos()) || m.level().getBlockEntity(m.pos()) == null;
            case Actor.Remembered r -> r.kind().equals(PLAYER) && r.id().isPresent()
                    && server.getPlayerList().getPlayer(r.id().get()) != null;
            default -> false;
        };
    }

    private @Nullable Actor resolve(MinecraftServer server) {
        entity = null;
        fixed = null;
        Actor actor = switch (Objects.requireNonNull(who)) {
            case ServerWho s -> new Actor.Server(s.name());
            case EntityWho e -> {
                Entity found = e.id().map(id -> find(server, e.kind(), id)).orElse(null);
                if (found != null) {
                    entity = new WeakReference<>(found);
                    yield EntityActors.as(found, e.attribution());
                }
                yield new Actor.Remembered(e.kind(), e.id(), e.attribution());
            }
            case MachineWho m -> {
                ServerLevel level = server.getLevel(m.at().dimension());
                BlockPos pos = m.at().pos();
                BlockEntity be = level != null && level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
                yield be != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).equals(m.kind())
                        ? new Actor.Machine(level, pos, m.attribution())
                        : new Actor.Remembered(m.kind(), Optional.empty(), m.attribution());
            }
        };
        if (entity == null) fixed = actor;
        return actor;
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
