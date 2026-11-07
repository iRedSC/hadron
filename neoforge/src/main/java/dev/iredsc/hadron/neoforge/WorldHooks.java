package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.player.PlayerAbilitiesChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerFlightRevokeEvent;
import dev.iredsc.hadron.api.event.player.PlayerKickEvent;
import dev.iredsc.hadron.api.event.player.PlayerLevelChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerLoginEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceUnloadEvent;
import dev.iredsc.hadron.api.event.world.PortalCreateEvent;
import dev.iredsc.hadron.api.event.world.TimeSkipEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import java.net.SocketAddress;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * What the world mixins call. Each checks {@link Hooks#listens} before building anything, so a server where no
 * plugin listens allocates nothing here.
 */
public final class WorldHooks {
    private WorldHooks() {}

    // ---- Weather and time

    /**
     * Whether a plugin refuses the weather about to be set. Only fires when what players see changes, and never for a
     * dimension that only mirrors another's weather (setting it there does nothing).
     */
    public static boolean weatherDenied(ServerLevel level, ServerLevelData data, boolean raining, boolean thundering,
            boolean natural) {
        if (data instanceof DerivedLevelData || !Hooks.listens(WeatherChangeEvent.class)) return false;
        WeatherChangeEvent.Weather from = WeatherChangeEvent.Weather.of(data.isRaining(), data.isThundering());
        WeatherChangeEvent.Weather to = WeatherChangeEvent.Weather.of(raining, thundering);
        if (from == to) return false;
        Actor actor = natural ? WorldActors.orNature() : WorldActors.orUnknown();
        return Hooks.post(new WeatherChangeEvent(actor, level, from, to)).isCancelled();
    }

    /**
     * The day time to set instead of {@code time}: the same when nobody objects, {@code old} when cancelled.
     *
     * @param sleep whether everyone slept through the night (nature's doing)
     */
    public static long dayTime(ServerLevel level, ServerLevelData data, long time, boolean sleep) {
        if (data instanceof DerivedLevelData || !Hooks.listens(TimeSkipEvent.class)) return time;
        long old = level.getDayTime();
        if (time == old) return time;
        Actor actor = sleep ? WorldActors.orNature() : WorldActors.orUnknown();
        TimeSkipEvent event = Hooks.post(new TimeSkipEvent(actor, level, old, time - old));
        return event.isCancelled() ? old : event.getNewTime();
    }

    // ---- Forced chunks

    /** Whether a plugin refuses a chunk being forced. */
    public static boolean forceDenied(Actor actor, ServerLevel level, ChunkPos pos, boolean ticking) {
        return Hooks.post(new ChunkForceLoadEvent(actor, level, pos, ticking)).isCancelled();
    }

    public static void forceReleased(Actor actor, ServerLevel level, ChunkPos pos) {
        Hooks.post(new ChunkForceUnloadEvent(actor, level, pos));
    }

    /**
     * Who forces a chunk through NeoForge's ticket controllers, from the ticket's owner when nothing is on the stack:
     * a block position is the machine there, a UUID the entity.
     */
    public static Actor ticketOwner(ServerLevel level, Object owner) {
        var current = Actors.current();
        if (current.isPresent()) return current.get();
        if (owner instanceof BlockPos pos) {
            BlockEntity be = level.getBlockEntity(pos);
            return new Actor.Machine(level, pos, be == null ? dev.iredsc.hadron.api.actor.Attribution.none()
                    : Actors.remembered(be));
        }
        if (owner instanceof UUID id) {
            Entity entity = level.getEntity(id);
            if (entity != null) return WorldActors.of(entity);
        }
        return Actor.Unknown.INSTANCE;
    }

    /**
     * A ticket a mod adds by hand to keep chunks loaded: permanent (no timeout) and not one of vanilla's own or
     * NeoForge's ticket controllers (those fire from {@code setChunkForced} and the controllers, which can refuse
     * before anything is saved).
     */
    public static boolean isModForceTicket(TicketType<?> type) {
        return type.timeout() == 0L && type != TicketType.START && type != TicketType.DRAGON
                && type != TicketType.PLAYER && type != TicketType.FORCED && !type.toString().startsWith("neoforge:");
    }

    // ---- Portals

    public static boolean portalDenied(ServerLevel level, BoundingBox area, PortalCreateEvent.CreateReason reason) {
        if (!Hooks.listens(PortalCreateEvent.class)) return false;
        return Hooks.post(new PortalCreateEvent(WorldActors.orUnknown(), level, area, reason)).isCancelled();
    }

    // ---- Players

    /** Null when the player may join, otherwise why not. */
    public static @Nullable Component loginDenied(ServerPlayer player, SocketAddress address) {
        if (!Hooks.listens(PlayerLoginEvent.class)) return null;
        return Hooks.post(new PlayerLoginEvent(player, address)).getKickMessage().orElse(null);
    }

    /** The reason to kick with, or null to keep the player connected. */
    public static @Nullable Component kick(ServerPlayer player, Component reason) {
        if (!Hooks.listens(PlayerKickEvent.class)) return reason;
        PlayerKickEvent event = Hooks.post(new PlayerKickEvent(player, reason));
        return event.isCancelled() ? null : event.getReason();
    }

    /**
     * Fires the abilities event for a change from what the client was last told. Returns false when a plugin
     * cancelled it, in which case the abilities have been put back.
     */
    public static boolean abilities(ServerPlayer player, boolean oldMayfly, float oldFly, float oldWalk) {
        Abilities a = player.getAbilities();
        if (oldMayfly && !a.mayfly) {
            if (Hooks.listens(PlayerFlightRevokeEvent.class)) {
                Hooks.post(new PlayerFlightRevokeEvent(player, oldFly, oldWalk, a.getFlyingSpeed(),
                        a.getWalkingSpeed()));
            }
            return true;
        }
        if (!Hooks.listens(PlayerAbilitiesChangeEvent.class)) return true;
        PlayerAbilitiesChangeEvent event = Hooks.post(new PlayerAbilitiesChangeEvent(player, oldMayfly, oldFly,
                oldWalk, a.mayfly, a.getFlyingSpeed(), a.getWalkingSpeed()));
        if (!event.isCancelled()) return true;
        a.mayfly = oldMayfly;
        if (!oldMayfly) a.flying = false;
        a.setFlyingSpeed(oldFly);
        a.setWalkingSpeed(oldWalk);
        return false;
    }

    public static void levelChanged(ServerPlayer player, int oldLevel, int newLevel) {
        if (Hooks.listens(PlayerLevelChangeEvent.class)) Hooks.post(new PlayerLevelChangeEvent(player, oldLevel, newLevel));
    }
}
