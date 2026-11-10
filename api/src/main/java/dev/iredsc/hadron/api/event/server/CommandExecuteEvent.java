package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Placed;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * Any command is about to run: typed by a player or the console, run by a command block, a quest reward, a function
 * or a mod. Bukkit splits this into {@code PlayerCommandPreprocessEvent} and {@code ServerCommandEvent}; here it's
 * one event, and {@link #getSource()} says who runs it. Rewrite it with {@link #setCommand}, or cancel so it doesn't
 * run.
 */
@FiredBy(core = FULL)
public class CommandExecuteEvent extends ServerEvent implements Cancellable, Placed {
    private final Actor actor;
    private final CommandSourceStack source;
    private String command;
    private boolean cancelled;

    public CommandExecuteEvent(Actor actor, CommandSourceStack source, String command) {
        this.actor = Objects.requireNonNull(actor, "actor; use Actor.Unknown.INSTANCE when nobody is known");
        this.source = Objects.requireNonNull(source, "source");
        this.command = Objects.requireNonNull(command, "command");
    }

    /**
     * Who is behind it. For a command a mod runs on a player's behalf, {@code getActor().attribution().authority()}
     * is whose permissions it runs with.
     */
    public Actor getActor() {
        return actor;
    }

    /** What runs it, with its permission level, position and entity; {@code getSource().getPlayer()} for a player. */
    public CommandSourceStack getSource() {
        return source;
    }

    /** The player behind it if online: the one who typed it, or the actor's controller or owner. */
    public Optional<ServerPlayer> getActingPlayer() {
        if (actor instanceof Actor.Player p) return Optional.of(p.player());
        ServerPlayer player = source.getPlayer();
        if (player != null) return Optional.of(player);
        return actor.actingFor().map(id -> source.getServer().getPlayerList().getPlayer(id));
    }

    /** The command without its leading slash, such as {@code give Steve diamond 2}. */
    public String getCommand() {
        return command;
    }

    /** Runs this instead; no leading slash. */
    public void setCommand(String command) {
        Objects.requireNonNull(command, "Command cannot be null; use setCancelled(true) to stop it running");
        this.command = command.startsWith("/") ? command.substring(1) : command;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * {@link Place.Unknown.Reason#COMMAND}, anywhere: a command's source (the console sits at spawn) isn't where it
     * acts. What it does fires its own events, which place themselves.
     */
    @Override
    public Places getPlaces() {
        return Places.of(Place.Unknown.anywhere(Place.Unknown.Reason.COMMAND));
    }
}
