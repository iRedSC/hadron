package dev.iredsc.hadron.core;

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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Plugins' commands in Minecraft's Brigadier dispatcher. Brigadier can only add, so taking a command out edits the
 * root node's maps directly. A datapack {@code /reload} builds a new dispatcher, so every live plugin command is
 * added again to each new one ({@link #apply}). Server thread only.
 */
final class Commands {
    private static final Field CHILDREN = field("children");
    private static final Field LITERALS = field("literals");
    private static final Field ARGUMENTS = field("arguments");

    /** What plugins registered and haven't undone, in registration order. */
    private static final List<Registration> live = new ArrayList<>();

    private Commands() {}

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

    /** One command a plugin added, and the node it displaced in the current dispatcher, if any. */
    private static final class Registration {
        final LiteralArgumentBuilder<CommandSourceStack> builder;
        CommandNode<CommandSourceStack> ours;
        CommandNode<CommandSourceStack> displaced;

        Registration(LiteralArgumentBuilder<CommandSourceStack> builder) {
            this.builder = builder;
        }
    }

    /** Adds the command to the running server now, and to every dispatcher a /reload makes. @return its undo. */
    static Runnable register(MinecraftServer server, LiteralArgumentBuilder<CommandSourceStack> builder) {
        Registration r = new Registration(builder);
        live.add(r);
        add(server.getCommands().getDispatcher(), r);
        resend(server);
        return () -> {
            live.remove(r);
            remove(server.getCommands().getDispatcher(), r);
            resend(server);
        };
    }

    /** A plugin.yml command, under its name, its aliases and {@code <plugin>:<name>}. @return one undo for all. */
    static Runnable register(MinecraftServer server, PluginCommand command, Logger log) {
        List<String> labels = new ArrayList<>();
        labels.add(command.getName());
        labels.addAll(command.getAliases());
        labels.add(command.getPlugin().getName().toLowerCase(Locale.ROOT) + ":" + command.getName());
        List<Runnable> undo = new ArrayList<>();
        for (String label : labels) undo.add(register(server, build(command, label, log)));
        return () -> undo.reversed().forEach(Runnable::run);
    }

    /** Called for each new dispatcher (server start, datapack /reload), after mods have added theirs. */
    static void apply(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (Registration r : live) {
            r.displaced = null;
            add(dispatcher, r);
        }
    }

    private static void add(CommandDispatcher<CommandSourceStack> dispatcher, Registration r) {
        String name = r.builder.getLiteral();
        CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(name);
        if (existing != null) {
            detach(dispatcher, name);
            r.displaced = existing;
        }
        r.ours = dispatcher.register(r.builder);
    }

    private static void remove(CommandDispatcher<CommandSourceStack> dispatcher, Registration r) {
        String name = r.builder.getLiteral();
        // Only if it's still ours: a later plugin taking the name over keeps it.
        if (dispatcher.getRoot().getChild(name) != r.ours) return;
        detach(dispatcher, name);
        if (r.displaced != null) dispatcher.getRoot().addChild(r.displaced);
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
        } catch (RuntimeException e) {
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
