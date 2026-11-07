package dev.iredsc.hadron.common;

import dev.iredsc.hadron.checks.StaticState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Plugins' commands in Minecraft's Brigadier dispatcher. Brigadier can only add, so taking a command out edits the
 * root node's maps directly. A datapack {@code /reload} builds a new dispatcher, so every live plugin command is
 * added again to each new one ({@link #apply}). Dispatcher preparation can run off the server thread.
 */
final class Commands {
    @StaticState(reason = "Brigadier's own child maps, opened once by reflection; the handle keeps no state of ours")
    private static final Field CHILDREN = field("children");
    @StaticState(reason = "Brigadier's own child maps, opened once by reflection; the handle keeps no state of ours")
    private static final Field LITERALS = field("literals");
    @StaticState(reason = "Brigadier's own child maps, opened once by reflection; the handle keeps no state of ours")
    private static final Field ARGUMENTS = field("arguments");

    /** What plugins registered and haven't undone, in registration order. */
    private final List<Registration> live = new ArrayList<>();
    private final Map<CommandDispatcher<CommandSourceStack>, Boolean> dispatchers = new WeakHashMap<>();
    private final MinecraftServer server;

    Commands(MinecraftServer server) {
        this.server = server;
    }

    private static Field field(String name) {
        try {
            Field f = CommandNode.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("This Brigadier's CommandNode changed shape (no '" + name + "'); Hadron "
                    + "can't remove plugin commands", e);
        }
    }

    /** One command a plugin added, with independent ownership in each prepared dispatcher. */
    private static final class Registration {
        final LiteralArgumentBuilder<CommandSourceStack> builder;
        final Map<CommandDispatcher<CommandSourceStack>, Binding> bindings = new WeakHashMap<>();

        Registration(LiteralArgumentBuilder<CommandSourceStack> builder) {
            this.builder = builder;
        }
    }

    private static final class Binding {
        final CommandNode<CommandSourceStack> ours;
        CommandNode<CommandSourceStack> displaced;

        Binding(CommandNode<CommandSourceStack> ours, CommandNode<CommandSourceStack> displaced) {
            this.ours = ours;
            this.displaced = displaced;
        }
    }

    /** Adds the command to the running server now, and to every dispatcher a /reload makes. @return its undo. */
    synchronized Runnable register(LiteralArgumentBuilder<CommandSourceStack> builder) {
        apply(server.getCommands().getDispatcher());
        Registration r = new Registration(builder);
        live.add(r);
        try {
            for (CommandDispatcher<CommandSourceStack> dispatcher : List.copyOf(dispatchers.keySet())) add(dispatcher, r);
            resend(server);
        } catch (RuntimeException | Error failure) {
            try {
                unregister(r);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return () -> unregister(r);
    }

    private synchronized void unregister(Registration r) {
        if (live.remove(r)) {
            for (var entry : new ArrayList<>(r.bindings.entrySet())) {
                CommandDispatcher<CommandSourceStack> dispatcher = entry.getKey();
                if (dispatcher == null) continue;
                Binding binding = entry.getValue();
                for (Registration other : live) {
                    Binding next = other.bindings.get(dispatcher);
                    if (next != null && next.displaced == binding.ours) next.displaced = binding.displaced;
                }
                remove(dispatcher, r.builder.getLiteral(), binding);
            }
            r.bindings.clear();
            resend(server);
        }
    }

    /** A plugin.yml command, under its name, its aliases and {@code <plugin>:<name>}. @return one undo for all. */
    synchronized Runnable register(PluginCommand command, Logger log) {
        List<String> labels = new ArrayList<>();
        labels.add(command.getName());
        labels.addAll(command.getAliases());
        labels.add(command.getPlugin().getName().toLowerCase(Locale.ROOT) + ":" + command.getName());
        List<Runnable> undo = new ArrayList<>();
        try {
            for (String label : labels) undo.add(register(build(command, label, log)));
        } catch (RuntimeException | Error failure) {
            undo.reversed().forEach(Runnable::run);
            throw failure;
        }
        return () -> undo.reversed().forEach(Runnable::run);
    }

    /** Called for each new dispatcher (server start, datapack /reload), after mods have added theirs. */
    synchronized void apply(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatchers.putIfAbsent(dispatcher, Boolean.TRUE) != null) return;
        for (Registration r : live) {
            add(dispatcher, r);
        }
    }

    private static void add(CommandDispatcher<CommandSourceStack> dispatcher, Registration r) {
        String name = r.builder.getLiteral();
        CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(name);
        if (existing != null) {
            detach(dispatcher, name);
        }
        r.bindings.put(dispatcher, new Binding(dispatcher.register(r.builder), existing));
    }

    private static void remove(CommandDispatcher<CommandSourceStack> dispatcher, String name, Binding binding) {
        // Only if it's still ours: a later plugin taking the name over keeps it.
        if (dispatcher.getRoot().getChild(name) != binding.ours) return;
        detach(dispatcher, name);
        if (binding.displaced != null) dispatcher.getRoot().addChild(binding.displaced);
    }

    @SuppressWarnings("unchecked")
    private static void detach(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        try {
            CommandNode<CommandSourceStack> root = dispatcher.getRoot();
            ((Map<String, ?>) CHILDREN.get(root)).remove(name);
            ((Map<String, ?>) LITERALS.get(root)).remove(name);
            ((Map<String, ?>) ARGUMENTS.get(root)).remove(name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** So players' chat suggestions show what's there now. */
    static void resend(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(player);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(PluginCommand command, String label, Logger log) {
        return net.minecraft.commands.Commands.literal(label)
                .requires(command::testPermissionSilent)
                .executes(ctx -> run(ctx, command, label, "", log))
                .then(net.minecraft.commands.Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggest(ctx, builder, command, label, log))
                        .executes(ctx -> run(ctx, command, label, StringArgumentType.getString(ctx, "args"), log)));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, PluginCommand command, String label, String line,
            Logger log) {
        CommandSourceStack sender = ctx.getSource();
        String[] args = line.isBlank() ? new String[0] : line.trim().split(" +");
        try {
            if (!command.getExecutor().onCommand(sender, command, label, args)) {
                sender.sendFailure(Component.literal(command.getUsage()));
                return 0;
            }
            return 1;
        } catch (Throwable e) {
            // Anything: an Error (an assert, a stack overflow, a class the plugin lost) escaping here would reach the
            // packet handler, which only survives Exceptions, and take the server thread with it.
            log.error("/" + label + " " + line + " threw", e);
            sender.sendFailure(Component.literal("/" + label + " failed: " + e).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder, PluginCommand command, String label,
            Logger log) {
        String typed = builder.getRemaining();
        String[] args = typed.split(" ", -1);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options;
        try {
            options = command.getTabCompleter().onTabComplete(ctx.getSource(), command, label, args);
        } catch (RuntimeException e) {
            log.error("Completing /" + label + " " + typed + " threw", e);
            return builder.buildFuture();
        }
        if (options == null) options = List.of(ctx.getSource().getServer().getPlayerNames());
        SuggestionsBuilder word = builder.createOffset(builder.getStart() + typed.lastIndexOf(' ') + 1);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) word.suggest(o);
        return word.buildFuture();
    }
}
