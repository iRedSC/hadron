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
final class ServerEvents {
    private ServerEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(ServerEvents::started);
        NeoForge.EVENT_BUS.addListener(ServerEvents::command);
    }

    private static void started(ServerStartedEvent event) {
        Hooks.post(new ServerLoadEvent(event.getServer()));
    }

    /** Commands.performCommand: every command, from players, the console, command blocks, functions and mods. */
    private static void command(CommandEvent event) {
        if (!Hooks.listens(CommandExecuteEvent.class)) return;
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        CommandSourceStack source = parse.getContext().getSource();
        String command = parse.getReader().getString();
        Actor actor = Actors.current().orElseGet(() -> source.getEntity() != null
                ? WorldActors.of(source.getEntity()) : Actor.Unknown.INSTANCE);
        CommandExecuteEvent hadron = Hooks.post(new CommandExecuteEvent(actor, source, command));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (!hadron.getCommand().equals(command)) {
            event.setParseResults(source.getServer().getCommands().getDispatcher().parse(hadron.getCommand(), source));
        }
    }
}
