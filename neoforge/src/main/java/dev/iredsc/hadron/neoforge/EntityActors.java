package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Who an entity is as an actor, and who is behind an effect on one. */
public final class EntityActors {
    private EntityActors() {}

    /**
     * The entity as an actor: a player, a fake player, or an entity carrying its remembered attribution. If a bridge
     * pushed this same entity with its own attribution (a turret firing for its owner), that actor wins. Cached.
     */
    public static Actor of(Entity entity) {
        Actor top = Actors.currentOr(null);
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

    /** The people behind an entity, to pass on to what it causes: a real player answers for themselves. */
    public static Attribution attributionOf(Entity cause) {
        if (cause instanceof ServerPlayer player && !(cause instanceof FakePlayer)) {
            return Attribution.of(player.getUUID());
        }
        return of(cause).attribution();
    }

    /** Whether an actor is the entity itself, so an entity hurting itself by walking into lava reads as nature. */
    public static boolean isSelf(Actor actor, Entity entity) {
        return switch (actor) {
            case Actor.Player p -> p.player() == entity;
            case Actor.FakePlayer f -> f.fake() == entity;
            case Actor.EntityActor e -> e.entity() == entity;
            default -> false;
        };
    }

    /** The actor on the stack, unless it is the subject itself or nothing: then the fallback. */
    public static Actor fromStack(Entity subject, Actor fallback) {
        Optional<Actor> current = Actors.current();
        if (current.isEmpty() || isSelf(current.get(), subject)) return fallback;
        return current.get();
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
     * Who hurt a living entity this tick, for effects that follow a hit (knockback). {@code hurtTime} equals
     * {@code hurtDuration} only in the tick the hit landed, so an older hit is never blamed.
     */
    public static Actor lastHit(LivingEntity entity) {
        DamageSource last = entity.getLastDamageSource();
        boolean thisTick = entity.hurtTime > 0 && entity.hurtTime == entity.hurtDuration;
        return last != null && thisTick ? damage(last, entity) : fromStack(entity, Actor.Nature.INSTANCE);
    }
}
