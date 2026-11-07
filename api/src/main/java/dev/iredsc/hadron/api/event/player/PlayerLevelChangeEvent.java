package dev.iredsc.hadron.api.event.player;

import net.minecraft.server.level.ServerPlayer;

/**
 * A player's experience level has changed, by any means: orbs, enchanting, an anvil, a command, dying. After the fact
 * (fired at the end of the tick it changed in): not cancellable. To stop experience being gained, use
 * {@link PlayerExpChangeEvent}.
 */
public class PlayerLevelChangeEvent extends PlayerEvent {
    private final int oldLevel;
    private final int newLevel;

    public PlayerLevelChangeEvent(ServerPlayer player, int oldLevel, int newLevel) {
        super(player);
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }
}
