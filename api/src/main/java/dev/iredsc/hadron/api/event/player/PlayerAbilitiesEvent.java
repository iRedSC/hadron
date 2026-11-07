package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerPlayer;

/**
 * A player's flight permission or movement speeds are changing: a game mode switch, a Flugel Tiara, a flight ritual, a
 * command. Listen for this to hear every change. Grants and speed changes are {@link PlayerAbilitiesChangeEvent},
 * which can be cancelled; losing flight is {@link PlayerFlightRevokeEvent}, which can't, so a temporary power always
 * ends.
 */
public abstract class PlayerAbilitiesEvent extends PlayerEvent {
    private final boolean oldAllowFlight;
    private final float oldFlySpeed;
    private final float oldWalkSpeed;
    private final boolean allowFlight;
    private final float flySpeed;
    private final float walkSpeed;

    protected PlayerAbilitiesEvent(ServerPlayer player, boolean oldAllowFlight, float oldFlySpeed, float oldWalkSpeed,
            boolean allowFlight, float flySpeed, float walkSpeed) {
        super(player);
        this.oldAllowFlight = oldAllowFlight;
        this.oldFlySpeed = oldFlySpeed;
        this.oldWalkSpeed = oldWalkSpeed;
        this.allowFlight = allowFlight;
        this.flySpeed = flySpeed;
        this.walkSpeed = walkSpeed;
    }

    /** Whether they may fly afterwards. */
    public boolean getAllowFlight() {
        return allowFlight;
    }

    public boolean getOldAllowFlight() {
        return oldAllowFlight;
    }

    /** Flying speed afterwards; vanilla's default is 0.05. */
    public float getFlySpeed() {
        return flySpeed;
    }

    public float getOldFlySpeed() {
        return oldFlySpeed;
    }

    /** Walking speed afterwards; vanilla's default is 0.1. */
    public float getWalkSpeed() {
        return walkSpeed;
    }

    public float getOldWalkSpeed() {
        return oldWalkSpeed;
    }
}
