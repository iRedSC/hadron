package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
import net.neoforged.neoforge.common.util.FakePlayer;

/** How the world, player and server chokepoints decide who acted. */
public final class WorldActors {
    private WorldActors() {}

    /** The actor on the stack, or {@link Actor.Unknown} when nothing said who. */
    public static Actor orUnknown() {
        return Actors.currentOr(Actor.Unknown.INSTANCE);
    }

    /** The actor on the stack, or {@link Actor.Nature} for vanilla's own processes with nobody behind them. */
    public static Actor orNature() {
        return Actors.currentOr(Actor.Nature.INSTANCE);
    }

    /** A real player, reusing the one actor made for them. */
    public static Actor player(ServerPlayer player) {
        if (player instanceof FakePlayer fake) return new Actor.FakePlayer(fake, Actors.remembered(fake));
        return ((PlayerState) player).hadron$actor();
    }

    /**
     * What an entity is as an actor: the stack's actor when it already names this entity (a bridge may have added an
     * owner), otherwise the entity with who was remembered behind it, or its owner if it has one. Null entity: the
     * stack, or unknown.
     */
    public static Actor of(Entity entity) {
        Optional<Actor> current = Actors.current();
        if (entity == null) return current.orElse(Actor.Unknown.INSTANCE);
        if (current.isPresent()) {
            Actor top = current.get();
            if (top instanceof Actor.EntityActor e && e.entity() == entity) return top;
            if (top instanceof Actor.Player p && p.player() == entity) return top;
            if (top instanceof Actor.FakePlayer f && f.fake() == entity) return top;
        }
        if (entity instanceof ServerPlayer player) return player(player);
        Attribution who = Actors.remembered(entity);
        if (who.isEmpty() && entity instanceof TraceableEntity traceable && traceable.getOwner() != null) {
            who = Attribution.of(traceable.getOwner().getUUID());
        }
        return new Actor.EntityActor(entity, who);
    }
}
