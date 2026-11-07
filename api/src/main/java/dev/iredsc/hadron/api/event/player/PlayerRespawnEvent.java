package dev.iredsc.hadron.api.event.player;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A player is about to respawn, after dying or leaving the End. Change where with {@link #setRespawnLocation}. Not
 * cancellable: a dead player has to come back somewhere.
 */
public class PlayerRespawnEvent extends PlayerEvent {
    private final boolean fromEndFight;
    private ServerLevel level;
    private Vec3 position;
    private float yaw;
    private float pitch;

    public PlayerRespawnEvent(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch,
            boolean fromEndFight) {
        super(player);
        this.level = level;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.fromEndFight = fromEndFight;
    }

    public ServerLevel getRespawnLevel() {
        return level;
    }

    public Vec3 getRespawnPosition() {
        return position;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    /** Respawns them there, facing the way they would have. */
    public void setRespawnLocation(ServerLevel level, Vec3 position) {
        setRespawnLocation(level, position, yaw, pitch);
    }

    public void setRespawnLocation(ServerLevel level, Vec3 position, float yaw, float pitch) {
        this.level = Objects.requireNonNull(level, "level");
        this.position = Objects.requireNonNull(position, "position");
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Whether they're coming back from the End's exit portal rather than from death. */
    public boolean isFromEndFight() {
        return fromEndFight;
    }
}
