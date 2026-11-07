package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerPlayer;

/**
 * A player has lost permission to fly (a flight item removed, a ritual expiring, survival mode). After the fact: not
 * cancellable, so temporary flight can't become permanent. Speeds changed at the same moment are reported here too.
 */
public class PlayerFlightRevokeEvent extends PlayerAbilitiesEvent {
    public PlayerFlightRevokeEvent(ServerPlayer player, float oldFlySpeed, float oldWalkSpeed, float flySpeed,
            float walkSpeed) {
        super(player, true, oldFlySpeed, oldWalkSpeed, false, flySpeed, walkSpeed);
    }
}
