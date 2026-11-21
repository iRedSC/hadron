package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Place;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Who an entity is as an actor, and who is behind an effect on one. */
public final class EntityActors {
    private static final Place UNSEEN = Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN);

    private EntityActors() {}

    /** The actor a bridge or Hadron pushed on this thread, or null when nothing did. */
    public static @Nullable Actor current() {
        return Actors.isEmpty() ? null : Actors.currentOr(Actor.Nature.INSTANCE);
    }

    /**
     * The entity as an actor: a player, a fake player, or an entity carrying its remembered attribution. If a bridge
     * pushed this same entity with its own attribution (a turret firing for its owner), that actor wins. Cached.
     */
    public static Actor of(Entity entity) {
        Actor top = EntityActors.current();
        if (top != null && isSelf(top, entity)) return top;
        EntityState state = (EntityState) entity;
        Actor actor = state.hadron$actor();
        if (actor == null) {
            actor = make(entity);
            state.hadron$setActor(actor);
        }
        return actor;
    }

    private static Actor make(Entity entity) {
        if (entity instanceof FakePlayer fake) return new Actor.FakePlayer(fake, Actors.remembered(fake));
        if (entity instanceof ServerPlayer player) return new Actor.Player(player);
        return new Actor.EntityActor(entity, Actors.remembered(entity));
    }

    /**
     * The entity as an actor for {@code attribution}, the people a cause had when it acted (a bridge's turret firing
     * for its owner): its own actor when that already says them, so nothing is allocated, otherwise one that does. A
     * real player always answers for themselves.
     */
    public static Actor as(Entity entity, Attribution attribution) {
        Actor own = of(entity);
        if (own.attribution().equals(attribution)) return own;
        if (entity instanceof FakePlayer fake) return new Actor.FakePlayer(fake, attribution);
        if (entity instanceof ServerPlayer) return own;
        return new Actor.EntityActor(entity, attribution);
    }

    /** The people behind an entity, to pass on to what it causes: a real player answers for themselves. */
    public static Attribution attributionOf(Entity cause) {
        if (cause instanceof ServerPlayer player && !(cause instanceof FakePlayer)) {
            return Attribution.of(player.getUUID());
        }
        return of(cause).attribution();
    }

    /** Whether an actor is the entity itself, so an entity hurting itself by walking into lava reads as nature. */
    public static boolean isSelf(Actor actor, @Nullable Entity entity) {
        return switch (actor) {
            case Actor.Player p -> p.player() == entity;
            case Actor.FakePlayer f -> f.fake() == entity;
            case Actor.EntityActor e -> e.entity() == entity;
            default -> false;
        };
    }

    /**
     * The actor on the stack, unless it is nothing, or the subject itself (an entity walking into lava hurts nobody's
     * interest but nature's): then the fallback. The subject is kept where an effect or fire acting on it put it there
     * as its cause (a player's own poison, see {@link LastingActs}).
     */
    public static Actor fromStack(Entity subject, Actor fallback) {
        Actor current = current();
        if (current == null) return fallback;
        if (isSelf(current, subject) && !LastingActs.isExplicitCause(subject, current)) return fallback;
        return current;
    }

    /** Who dealt damage: the arrow (which remembers its shooter), the attacker, or what the stack says. */
    public static Actor damage(DamageSource source, Entity victim) {
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != victim) return of(direct);
        Entity cause = source.getEntity();
        if (cause != null && cause != victim) return of(cause);
        return fromStack(victim, Actor.Nature.INSTANCE);
    }

    /**
     * Where a hit nothing applied earlier set out from ({@code EntityDamageEvent.getSourceLocation()}), given the
     * actor {@link #damage} found for it. In order: the position the source names (a bed's blast); the entity that
     * hit, unless it is the victim (a projectile from where it entered the world, a fake player from the machine
     * acting, anything else from where it is); a cause named with nothing that carried it, or a blast or shot that
     * names nothing, is unknown; otherwise whoever acts: a machine or an entity from where it is, a gone or unknown
     * cause unknown, and the server or nature from nowhere at all (a fall, drowning, a command).
     */
    public static Optional<Place> sourceLocation(DamageSource source, LivingEntity victim, Actor actor) {
        Vec3 at = source.sourcePositionRaw();
        if (at != null) return Optional.of(Place.Spot.at((ServerLevel) victim.level(), at));
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != victim) return Optional.of(hitFrom(direct));
        Entity cause = source.getEntity();
        if (cause != null && cause != victim) return Optional.of(UNSEEN);
        if (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.IS_PROJECTILE)) {
            return Optional.of(UNSEEN);
        }
        return switch (actor) {
            case Actor.Machine m -> Optional.of(Place.Spot.at(m.level(), m.pos()));
            case Actor.Player p -> Optional.of(standing(p.player()));
            case Actor.EntityActor e -> Optional.of(standing(e.entity()));
            case Actor.FakePlayer f -> Optional.of(machineActingOrUnseen());
            case Actor.Remembered r -> Optional.of(UNSEEN);
            case Actor.Unknown u -> Optional.of(UNSEEN);
            case Actor.Server s -> Optional.empty();
            case Actor.Nature n -> Optional.empty();
        };
    }

    /** Where an entity that hit sent the hit from. */
    private static Place hitFrom(Entity direct) {
        if (direct instanceof FakePlayer) return machineActingOrUnseen();
        if (direct instanceof Projectile) return Actors.origin(direct);
        return standing(direct);
    }

    /** A fake player stands wherever its mod left it: the machine acting for it, if one is, otherwise unknown. */
    private static Place machineActingOrUnseen() {
        return current() instanceof Actor.Machine m ? Place.Spot.at(m.level(), m.pos()) : UNSEEN;
    }

    private static Place standing(Entity entity) {
        return Place.Spot.at((ServerLevel) entity.level(), entity.position());
    }

    /** A living entity's hit in progress: the damage source while {@code hurt} runs, otherwise null. */
    public interface Hurting {
        @Nullable DamageSource hadron$hurting();
    }

    /**
     * Who hurt a living entity, for effects that follow a hit (knockback). Inside the hit, its own source: Minecraft
     * updates the remembered last damage only after the knockback, so that would still be the previous hit's. After
     * the hit (an attacker's own knockback), the remembered damage, if it landed this tick: {@code hurtTime} equals
     * {@code hurtDuration} only then, so an older hit is never blamed.
     */
    public static Actor lastHit(LivingEntity entity) {
        DamageSource current = ((Hurting) entity).hadron$hurting();
        if (current != null) return damage(current, entity);
        DamageSource last = entity.getLastDamageSource();
        boolean thisTick = entity.hurtTime > 0 && entity.hurtTime == entity.hurtDuration;
        return last != null && thisTick ? damage(last, entity) : fromStack(entity, Actor.Nature.INSTANCE);
    }
}
