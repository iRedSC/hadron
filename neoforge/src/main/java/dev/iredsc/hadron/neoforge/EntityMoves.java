package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The block-crossing check behind {@link EntityMoveEvent} and {@link PlayerMoveEvent}, run before each entity's tick.
 * Only players (not riding anything) and vehicles a player drives are checked. The last position lives in fields on
 * the entity, and nothing is allocated unless the entity changed block and someone listens.
 */
public final class EntityMoves {
    private EntityMoves() {}

    /** Called from the server tick, before the entity ticks. */
    public static void beforeTick(Entity entity) {
        EntityState state = (EntityState) entity;
        boolean player = entity instanceof ServerPlayer;
        LivingEntity driver = player ? null : entity.getControllingPassenger();
        if (player ? entity.isPassenger() : !(driver instanceof Player)) {
            if (state.hadron$tracked()) state.hadron$untrack();
            return;
        }
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        if (!state.hadron$tracked() || state.hadron$trackedLevel() != entity.level()
                || (Mth.floor(x) == Mth.floor(state.hadron$trackedX())
                        && Mth.floor(y) == Mth.floor(state.hadron$trackedY())
                        && Mth.floor(z) == Mth.floor(state.hadron$trackedZ()))) {
            state.hadron$track(entity.level(), x, y, z);
            return;
        }
        Class<? extends Event> type = player ? PlayerMoveEvent.class : EntityMoveEvent.class;
        if (!Hooks.listens(type)) {
            state.hadron$track(entity.level(), x, y, z);
            return;
        }
        Vec3 from = new Vec3(state.hadron$trackedX(), state.hadron$trackedY(), state.hadron$trackedZ());
        Vec3 to = new Vec3(x, y, z);
        EntityMoveEvent event = player
                ? new PlayerMoveEvent((ServerPlayer) entity, from, to)
                : new EntityMoveEvent(entity, from, to, EntityActors.of(driver));
        if (!Hooks.post(event).isCancelled()) {
            state.hadron$track(entity.level(), x, y, z);
            return;
        }
        putBack(entity, driver, from);
        state.hadron$track(entity.level(), from.x, from.y, from.z);
    }

    private static void putBack(Entity entity, LivingEntity driver, Vec3 from) {
        if (entity instanceof ServerPlayer player) {
            player.connection.teleport(from.x, from.y, from.z, player.getYRot(), player.getXRot());
            return;
        }
        EntityTeleports.quietly(() -> entity.teleportTo(from.x, from.y, from.z));
        entity.setDeltaMovement(Vec3.ZERO);
        // The driver's client moves the vehicle itself; tell it where the vehicle really is.
        if (driver instanceof ServerPlayer player) player.connection.send(new ClientboundMoveVehiclePacket(entity));
    }

    /** After a teleport, start from where the entity landed rather than seeing the jump as a move. */
    static void retrack(Entity entity) {
        EntityState state = (EntityState) entity;
        if (state.hadron$tracked()) state.hadron$track(entity.level(), entity.getX(), entity.getY(), entity.getZ());
    }
}
