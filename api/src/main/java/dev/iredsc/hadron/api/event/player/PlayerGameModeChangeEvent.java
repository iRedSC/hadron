package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/** A player's game mode is about to change, by a command, a plugin or a mod. Cancel to keep the old one. */
@FiredBy(core = FULL)
public class PlayerGameModeChangeEvent extends PlayerEvent implements Cancellable {
    private final GameType oldGameMode;
    private final GameType newGameMode;
    private boolean cancelled;

    public PlayerGameModeChangeEvent(ServerPlayer player, GameType oldGameMode, GameType newGameMode) {
        super(player);
        this.oldGameMode = oldGameMode;
        this.newGameMode = newGameMode;
    }

    public GameType getNewGameMode() {
        return newGameMode;
    }

    public GameType getOldGameMode() {
        return oldGameMode;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
