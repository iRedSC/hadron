package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/** A player died. Their items haven't dropped yet. */
public class PlayerDeathEvent extends PlayerEvent {
    private final DamageSource source;

    public PlayerDeathEvent(ServerPlayer player, DamageSource source) {
        super(player);
        this.source = source;
    }

    /** What killed them; {@code getSource().getEntity()} is the killer, if any. */
    public DamageSource getSource() {
        return source;
    }
}
