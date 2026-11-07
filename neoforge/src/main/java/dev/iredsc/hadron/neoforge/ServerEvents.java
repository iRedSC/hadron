package dev.iredsc.hadron.neoforge;

import com.mojang.brigadier.ParseResults;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.server.CommandExecuteEvent;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Hadron's server events from NeoForge's. */
public final class ServerEvents {
    private ServerEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(ServerEvents::started);
        NeoForge.EVENT_BUS.addListener(ServerEvents::command);
    }

    private static void started(ServerStartedEvent event) {
        Hooks.post(new ServerLoadEvent(event.getServer()));
    }

    /**
     * Commands.performCommand: every command typed or sent whole, from players, the console, command blocks and mods.
     * A datapack function's lines skip it; they are asked about one by one through {@link #ask} as they run.
     */
    private static void command(CommandEvent event) {
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        CommandSourceStack source = parse.getContext().getSource();
        String command = parse.getReader().getString();
        String run = ask(source, command);
        if (run == null) {
            event.setCanceled(true);
        } else if (!run.equals(command)) {
            event.setParseResults(source.getServer().getCommands().getDispatcher().parse(run, source));
        }
    }

    /** A function line a listener rewrote, which runs without being asked about again. */
    public interface Rewritten {
        void hadron$markAsked();
    }

    /** Fires {@link CommandExecuteEvent} for a command about to run: the command to run instead, or null to refuse. */
    public static String ask(CommandSourceStack source, String command) {
        if (!Hooks.listens(CommandExecuteEvent.class)) return command;
        Actor actor = Actors.current().orElseGet(() -> source.getEntity() != null
                ? WorldActors.of(source.getEntity()) : Actor.Unknown.INSTANCE);
        CommandExecuteEvent hadron = Hooks.post(new CommandExecuteEvent(actor, source, command));
        return hadron.isCancelled() ? null : hadron.getCommand();
    }
}
